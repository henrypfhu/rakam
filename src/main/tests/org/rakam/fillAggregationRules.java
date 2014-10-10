package org.rakam;

import org.rakam.analysis.query.simple.SimpleFieldScript;
import org.rakam.analysis.rule.aggregation.AnalysisRule;
import org.rakam.analysis.rule.aggregation.MetricAggregationRule;
import org.rakam.analysis.rule.aggregation.TimeSeriesAggregationRule;
import org.rakam.constant.AggregationType;
import org.rakam.util.SpanTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Created by buremba on 04/05/14.
 */
public class fillAggregationRules {
    public static void fill(Map<String, HashSet<AnalysisRule>> aggregation_map) {
        HashSet<AnalysisRule> aggs = new HashSet<AnalysisRule>();
        String projectId = "e74607921dad4803b998";
        aggs.add(new MetricAggregationRule(projectId, AggregationType.COUNT_X, new SimpleFieldScript("test")));
        aggs.add(new MetricAggregationRule(projectId, AggregationType.SUM_X, new SimpleFieldScript("test")));
        aggs.add(new MetricAggregationRule(projectId, AggregationType.MAXIMUM_X, new SimpleFieldScript("test")));
        aggs.add(new TimeSeriesAggregationRule(projectId, AggregationType.UNIQUE_X, SpanTime.fromString("1min").period,  new SimpleFieldScript("baska"), null,  new SimpleFieldScript("referral")));

        HashMap<String, Object> a = new HashMap();
        a.put("a", "a");
        //aggs.add(new MetricAggregationRule(projectId, AggregationType.AVERAGE_X,  new SimpleFieldScript("test"), new SimpleFilterScript(a)));
        aggs.add(new TimeSeriesAggregationRule(projectId, AggregationType.COUNT_X, SpanTime.fromString("1min").period,  new SimpleFieldScript("referral")));
        aggs.add(new TimeSeriesAggregationRule(projectId, AggregationType.COUNT, SpanTime.fromString("1min").period, null, null));

        // tracker_id -> aggregation rules
        aggregation_map.put("e74607921dad4803b998", aggs);
    }
}