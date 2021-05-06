/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.full;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.annotations.documented.ReporterFactory;
import org.neo4j.configuration.Config;
import org.neo4j.consistency.RecordType;
import org.neo4j.consistency.checker.NodeBasedMemoryLimiter;
import org.neo4j.consistency.checker.RecordStorageConsistencyChecker;
import org.neo4j.consistency.checking.index.IndexAccessors;
import org.neo4j.consistency.report.ConsistencyReporter;
import org.neo4j.consistency.report.ConsistencySummaryStatistics;
import org.neo4j.consistency.report.InconsistencyMessageLogger;
import org.neo4j.consistency.report.InconsistencyReport;
import org.neo4j.consistency.store.DirectStoreAccess;
import org.neo4j.counts.CountsStore;
import org.neo4j.function.ThrowingSupplier;
import org.neo4j.internal.counts.RelationshipGroupDegreesStore;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.impl.api.index.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.index.schema.ConsistencyCheckable;
import org.neo4j.kernel.impl.index.schema.RelationshipTypeScanStoreSettings;
import org.neo4j.logging.Log;
import org.neo4j.memory.MemoryTracker;

public class FullCheck
{
    private static final String INDEX_STRUCTURE_CHECKER_TAG = "indexStructureChecker";
    private final Config config;
    private final boolean verbose;
    private final NodeBasedMemoryLimiter.Factory memoryLimit;
    private final ProgressMonitorFactory progressFactory;
    private final IndexSamplingConfig samplingConfig;
    private final int threads;
    private final ConsistencyFlags flags;

    public FullCheck( ProgressMonitorFactory progressFactory, int threads,
                      ConsistencyFlags consistencyFlags, Config config, boolean verbose, NodeBasedMemoryLimiter.Factory memoryLimit )
    {
        this.threads = threads;
        this.progressFactory = progressFactory;
        this.flags = consistencyFlags;
        this.samplingConfig = new IndexSamplingConfig( config );
        this.config = config;
        this.verbose = verbose;
        this.memoryLimit = memoryLimit;
    }

    public ConsistencySummaryStatistics execute( PageCache pageCache, DirectStoreAccess stores, ThrowingSupplier<CountsStore,IOException> countsSupplier,
            ThrowingSupplier<RelationshipGroupDegreesStore,IOException> groupDegreesStoreSupplier,
            IndexAccessors.IndexAccessorLookup indexAccessorLookup, PageCacheTracer pageCacheTracer, MemoryTracker memoryTracker, Log log )
            throws ConsistencyCheckIncompleteException
    {
        ConsistencySummaryStatistics summary = new ConsistencySummaryStatistics();
        InconsistencyReport report = new InconsistencyReport( new InconsistencyMessageLogger( log ), summary );
        CountsStore countsStore = getCountsStore( countsSupplier, log, summary );
        RelationshipGroupDegreesStore groupDegreesStore = getGroupDegreesStore( groupDegreesStoreSupplier, log, summary );
        execute( pageCache, stores, report, countsStore, groupDegreesStore, indexAccessorLookup, pageCacheTracer, memoryTracker );

        if ( !summary.isConsistent() )
        {
            log.warn( "Inconsistencies found: " + summary );
        }
        return summary;
    }

    private CountsStore getCountsStore( ThrowingSupplier<CountsStore,IOException> countsSupplier, Log log, ConsistencySummaryStatistics summary )
    {
        // Perhaps other read-only use cases thinks it's fine to just rebuild an in-memory counts store,
        // but the consistency checker should instead prevent rebuild and report that the counts store is broken or missing
        CountsStore countsStore = CountsStore.NULL_INSTANCE;
        if ( flags.isCheckGraph() || flags.isCheckIndexStructure() )
        {
            try
            {
                countsStore = countsSupplier.get();
            }
            catch ( Exception e )
            {
                log.error( "Counts store is missing, broken or of an older format and will not be consistency checked", e );
                summary.update( RecordType.COUNTS, 1, 0 );
            }
        }
        return countsStore;
    }

    private RelationshipGroupDegreesStore getGroupDegreesStore( ThrowingSupplier<RelationshipGroupDegreesStore,IOException> groupDegreesStoreSupplier, Log log,
            ConsistencySummaryStatistics summary )
    {
        RelationshipGroupDegreesStore store = null;
        if ( flags.isCheckIndexStructure() )
        {
            try
            {
                store = groupDegreesStoreSupplier.get();
            }
            catch ( Exception e )
            {
                log.error( "Relationship group degrees store is missing, broken or of an older format and will not be consistency checked", e );
                summary.update( RecordType.RELATIONSHIP_GROUP, 1, 0 );
            }
        }
        return store;
    }

    void execute( PageCache pageCache, final DirectStoreAccess directStoreAccess, final InconsistencyReport report, CountsStore countsStore,
            RelationshipGroupDegreesStore groupDegreesStore,
            IndexAccessors.IndexAccessorLookup indexAccessorLookup, PageCacheTracer pageCacheTracer, MemoryTracker memoryTracker )
            throws ConsistencyCheckIncompleteException
    {
        try ( IndexAccessors indexes = new IndexAccessors( directStoreAccess.indexes(), directStoreAccess.nativeStores(), samplingConfig,
                indexAccessorLookup, pageCacheTracer, directStoreAccess.tokenHolders().lookupWithIds(), config,
                directStoreAccess.nativeStores().getMetaDataStore() ) )
        {
            if ( flags.isCheckIndexStructure() )
            {
                ConsistencyCheckable labelScanStore = getLabelScanStructure( directStoreAccess, indexes );
                ConsistencyCheckable relationshipTypeIndex = getRelationshipTypeIndex( directStoreAccess, indexes );

                consistencyCheckIndexStructure( labelScanStore, relationshipTypeIndex,
                        directStoreAccess.indexStatisticsStore(), countsStore, groupDegreesStore, indexes, allIdGenerators( directStoreAccess ), report,
                        progressFactory, pageCacheTracer );
            }

            try ( RecordStorageConsistencyChecker checker = new RecordStorageConsistencyChecker( pageCache,
                    directStoreAccess.nativeStores(), countsStore, directStoreAccess.labelScanStore(),
                    indexes, report, progressFactory, config, threads, verbose, flags, memoryLimit,
                    pageCacheTracer, memoryTracker ) )
            {
                checker.check();
            }
        }
        catch ( Exception e )
        {
            throw new ConsistencyCheckIncompleteException( e );
        }
    }

    private ConsistencyCheckable getRelationshipTypeIndex( DirectStoreAccess directStoreAccess, IndexAccessors indexes )
    {
        ConsistencyCheckable result = ( reporterFactory, cursorContext ) -> true;
        if ( config.get( RelationshipTypeScanStoreSettings.enable_scan_stores_as_token_indexes ) )
        {
            ConsistencyCheckable relationshipTypeIndex = indexes.relationshipTypeIndex();
            if ( relationshipTypeIndex != null )
            {
                result = relationshipTypeIndex;
            }
        }
        return result;
    }

    private ConsistencyCheckable getLabelScanStructure( DirectStoreAccess directStoreAccess, IndexAccessors indexes )
    {
        ConsistencyCheckable labelScanStore;
        if ( config.get( RelationshipTypeScanStoreSettings.enable_scan_stores_as_token_indexes ) )
        {
            labelScanStore = indexes.nodeLabelIndex();
            if ( labelScanStore == null )
            {
                labelScanStore = ( reporterFactory, cursorContext ) -> true;
            }
        }
        else
        {
            labelScanStore = directStoreAccess.labelScanStore();
        }
        return labelScanStore;
    }

    private static List<IdGenerator> allIdGenerators( DirectStoreAccess directStoreAccess )
    {
        List<IdGenerator> idGenerators = new ArrayList<>();
        directStoreAccess.idGeneratorFactory().visit( idGenerators::add );
        return idGenerators;
    }

    private static void consistencyCheckIndexStructure( ConsistencyCheckable labelScanStore,
            ConsistencyCheckable relationshipTypeScanStore, IndexStatisticsStore indexStatisticsStore,
            CountsStore countsStore, RelationshipGroupDegreesStore groupDegreesStore, IndexAccessors indexes,
            List<IdGenerator> idGenerators, InconsistencyReport report, ProgressMonitorFactory progressMonitorFactory, PageCacheTracer pageCacheTracer )
    {
        try ( var cursorContext = new CursorContext( pageCacheTracer.createPageCursorTracer( INDEX_STRUCTURE_CHECKER_TAG ) ) )
        {
            final long schemaIndexCount = Iterables.count( indexes.onlineRules() );
            long additionalCount = 1 /*LabelScanStore*/ + 1 /*RelationshipTypeScanStore*/ + 1 /*IndexStatisticsStore*/ + 1 /*countsStore*/;
            if ( hasGroupDegreesStore( groupDegreesStore ) )
            {
                additionalCount += 1;
            }
            final long idGeneratorsCount = idGenerators.size();
            final long totalCount = schemaIndexCount + additionalCount + idGeneratorsCount;
            var listener = progressMonitorFactory.singlePart( "Index structure consistency check", totalCount );
            listener.started();

            consistencyCheckNonSchemaIndexes( report, listener, labelScanStore, relationshipTypeScanStore, indexStatisticsStore, countsStore, groupDegreesStore,
                    idGenerators, cursorContext );
            consistencyCheckSchemaIndexes( indexes, report, listener, cursorContext );
            listener.done();
        }
    }

    private static void consistencyCheckNonSchemaIndexes( InconsistencyReport report, ProgressListener listener,
            ConsistencyCheckable labelScanStore, ConsistencyCheckable relationshipTypeScanStore,
            IndexStatisticsStore indexStatisticsStore, CountsStore countsStore, RelationshipGroupDegreesStore groupDegreesStore, List<IdGenerator> idGenerators,
            CursorContext cursorContext )
    {
        consistencyCheckSingleCheckable( report, listener, labelScanStore, RecordType.LABEL_SCAN_DOCUMENT, cursorContext );
        consistencyCheckSingleCheckable( report, listener, relationshipTypeScanStore, RecordType.RELATIONSHIP_TYPE_SCAN_DOCUMENT, cursorContext );
        consistencyCheckSingleCheckable( report, listener, indexStatisticsStore, RecordType.INDEX_STATISTICS, cursorContext );
        consistencyCheckSingleCheckable( report, listener, countsStore, RecordType.COUNTS, cursorContext );
        if ( hasGroupDegreesStore( groupDegreesStore ) )
        {
            // The relationship group degrees store has no "NULL_STORE" because it's otherwise not needed and it's not
            // checked in the other parts of the consistency checker since degrees in general aren't checked (due to performance implications).
            // This is why we use null instead for this particular store.
            consistencyCheckSingleCheckable( report, listener, groupDegreesStore, RecordType.RELATIONSHIP_GROUP, cursorContext );
        }
        for ( IdGenerator idGenerator : idGenerators )
        {
            consistencyCheckSingleCheckable( report, listener, idGenerator, RecordType.ID_STORE, cursorContext );
        }
    }

    private static void consistencyCheckSingleCheckable( InconsistencyReport report, ProgressListener listener, ConsistencyCheckable checkable,
            RecordType recordType, CursorContext cursorContext )
    {
        ConsistencyReporter.FormattingDocumentedHandler handler = ConsistencyReporter.formattingHandler( report, recordType );
        ReporterFactory proxyFactory = new ReporterFactory( handler );

        checkable.consistencyCheck( proxyFactory, cursorContext );
        handler.updateSummary();
        listener.add( 1 );
    }

    private static void consistencyCheckSchemaIndexes( IndexAccessors indexes, InconsistencyReport report, ProgressListener listener,
            CursorContext cursorContext )
    {
        List<IndexDescriptor> rulesToRemove = new ArrayList<>();
        for ( IndexDescriptor onlineRule : indexes.onlineRules() )
        {
            ConsistencyReporter.FormattingDocumentedHandler handler = ConsistencyReporter.formattingHandler( report, RecordType.INDEX );
            ReporterFactory reporterFactory = new ReporterFactory( handler );
            IndexAccessor accessor = indexes.accessorFor( onlineRule );
            if ( !accessor.consistencyCheck( reporterFactory, cursorContext ) )
            {
                rulesToRemove.add( onlineRule );
            }
            handler.updateSummary();
            listener.add( 1 );
        }
        for ( IndexDescriptor toRemove : rulesToRemove )
        {
            indexes.remove( toRemove );
        }
    }

    private static boolean hasGroupDegreesStore( RelationshipGroupDegreesStore groupDegreesStore )
    {
        return groupDegreesStore != null;
    }
}
