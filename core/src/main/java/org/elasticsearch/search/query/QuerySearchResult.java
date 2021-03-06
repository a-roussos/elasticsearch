/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.query;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.SiblingPipelineAggregator;
import org.elasticsearch.search.profile.ProfileShardResult;
import org.elasticsearch.search.suggest.Suggest;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static org.elasticsearch.common.lucene.Lucene.readTopDocs;
import static org.elasticsearch.common.lucene.Lucene.writeTopDocs;

public final class QuerySearchResult extends QuerySearchResultProvider {

    private long id;
    private SearchShardTarget shardTarget;
    private int from;
    private int size;
    private TopDocs topDocs;
    private DocValueFormat[] sortValueFormats;
    private InternalAggregations aggregations;
    private boolean hasAggs;
    private List<SiblingPipelineAggregator> pipelineAggregators;
    private Suggest suggest;
    private boolean searchTimedOut;
    private Boolean terminatedEarly = null;
    private ProfileShardResult profileShardResults;
    private boolean hasProfileResults;

    public QuerySearchResult() {
    }

    public QuerySearchResult(long id, SearchShardTarget shardTarget) {
        this.id = id;
        this.shardTarget = shardTarget;
    }

    @Override
    public QuerySearchResult queryResult() {
        return this;
    }

    @Override
    public long id() {
        return this.id;
    }

    @Override
    public SearchShardTarget shardTarget() {
        return shardTarget;
    }

    @Override
    public void shardTarget(SearchShardTarget shardTarget) {
        this.shardTarget = shardTarget;
    }

    public void searchTimedOut(boolean searchTimedOut) {
        this.searchTimedOut = searchTimedOut;
    }

    public boolean searchTimedOut() {
        return searchTimedOut;
    }

    public void terminatedEarly(boolean terminatedEarly) {
        this.terminatedEarly = terminatedEarly;
    }

    public Boolean terminatedEarly() {
        return this.terminatedEarly;
    }

    public TopDocs topDocs() {
        return topDocs;
    }

    public void topDocs(TopDocs topDocs, DocValueFormat[] sortValueFormats) {
        this.topDocs = topDocs;
        if (topDocs.scoreDocs.length > 0 && topDocs.scoreDocs[0] instanceof FieldDoc) {
            int numFields = ((FieldDoc) topDocs.scoreDocs[0]).fields.length;
            if (numFields != sortValueFormats.length) {
                throw new IllegalArgumentException("The number of sort fields does not match: "
                        + numFields + " != " + sortValueFormats.length);
            }
        }
        this.sortValueFormats = sortValueFormats;
    }

    public DocValueFormat[] sortValueFormats() {
        return sortValueFormats;
    }

    /**
     * Retruns <code>true</code> if this query result has unconsumed aggregations
     */
    public boolean hasAggs() {
        return hasAggs;
    }

    /**
     * Returns and nulls out the aggregation for this search results. This allows to free up memory once the aggregation is consumed.
     * @throws IllegalStateException if the aggregations have already been consumed.
     */
    public Aggregations consumeAggs() {
        if (aggregations == null) {
            throw new IllegalStateException("aggs already consumed");
        }
        Aggregations aggs = aggregations;
        aggregations = null;
        return aggs;
    }

    public void aggregations(InternalAggregations aggregations) {
        this.aggregations = aggregations;
        hasAggs = aggregations != null;
    }

    /**
     * Returns and nulls out the profiled results for this search, or potentially null if result was empty.
     * This allows to free up memory once the profiled result is consumed.
     * @throws IllegalStateException if the profiled result has already been consumed.
     */
    public ProfileShardResult consumeProfileResult() {
        if (profileShardResults == null) {
            throw new IllegalStateException("profile results already consumed");
        }
        ProfileShardResult result = profileShardResults;
        profileShardResults = null;
        return result;
    }

    public boolean hasProfileResults() {
        return hasProfileResults;
    }

    /**
     * Sets the finalized profiling results for this query
     * @param shardResults The finalized profile
     */
    public void profileResults(ProfileShardResult shardResults) {
        this.profileShardResults = shardResults;
        hasProfileResults = shardResults != null;
    }

    public List<SiblingPipelineAggregator> pipelineAggregators() {
        return pipelineAggregators;
    }

    public void pipelineAggregators(List<SiblingPipelineAggregator> pipelineAggregators) {
        this.pipelineAggregators = pipelineAggregators;
    }

    public Suggest suggest() {
        return suggest;
    }

    public void suggest(Suggest suggest) {
        this.suggest = suggest;
    }

    public int from() {
        return from;
    }

    public QuerySearchResult from(int from) {
        this.from = from;
        return this;
    }

    /**
     * Returns the maximum size of this results top docs.
     */
    public int size() {
        return size;
    }

    public QuerySearchResult size(int size) {
        this.size = size;
        return this;
    }

    /** Returns true iff the result has hits */
    public boolean hasHits() {
        return (topDocs != null && topDocs.scoreDocs.length > 0) ||
            (suggest != null && suggest.hasScoreDocs());
    }

    public static QuerySearchResult readQuerySearchResult(StreamInput in) throws IOException {
        QuerySearchResult result = new QuerySearchResult();
        result.readFrom(in);
        return result;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        long id = in.readLong();
        readFromWithId(id, in);
    }

    public void readFromWithId(long id, StreamInput in) throws IOException {
        this.id = id;
        from = in.readVInt();
        size = in.readVInt();
        int numSortFieldsPlus1 = in.readVInt();
        if (numSortFieldsPlus1 == 0) {
            sortValueFormats = null;
        } else {
            sortValueFormats = new DocValueFormat[numSortFieldsPlus1 - 1];
            for (int i = 0; i < sortValueFormats.length; ++i) {
                sortValueFormats[i] = in.readNamedWriteable(DocValueFormat.class);
            }
        }
        topDocs = readTopDocs(in);
        if (hasAggs = in.readBoolean()) {
            aggregations = InternalAggregations.readAggregations(in);
        }
        pipelineAggregators = in.readNamedWriteableList(PipelineAggregator.class).stream().map(a -> (SiblingPipelineAggregator) a)
                .collect(Collectors.toList());
        if (in.readBoolean()) {
            suggest = Suggest.readSuggest(in);
        }
        searchTimedOut = in.readBoolean();
        terminatedEarly = in.readOptionalBoolean();
        profileShardResults = in.readOptionalWriteable(ProfileShardResult::new);
        hasProfileResults = profileShardResults != null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeLong(id);
        writeToNoId(out);
    }

    public void writeToNoId(StreamOutput out) throws IOException {
        out.writeVInt(from);
        out.writeVInt(size);
        if (sortValueFormats == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(1 + sortValueFormats.length);
            for (int i = 0; i < sortValueFormats.length; ++i) {
                out.writeNamedWriteable(sortValueFormats[i]);
            }
        }
        writeTopDocs(out, topDocs);
        if (aggregations == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            aggregations.writeTo(out);
        }
        out.writeNamedWriteableList(pipelineAggregators == null ? emptyList() : pipelineAggregators);
        if (suggest == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            suggest.writeTo(out);
        }
        out.writeBoolean(searchTimedOut);
        out.writeOptionalBoolean(terminatedEarly);
        out.writeOptionalWriteable(profileShardResults);
    }
}
