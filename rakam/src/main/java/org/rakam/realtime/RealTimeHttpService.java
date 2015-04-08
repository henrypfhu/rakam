package org.rakam.realtime;

import com.facebook.presto.jdbc.internal.guava.collect.ImmutableMap;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Expression;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import org.rakam.collection.event.metastore.ReportMetadataStore;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.report.QueryExecutor;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.util.JsonHelper;
import org.rakam.util.json.JsonResponse;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.rakam.server.http.HttpServer.errorMessage;
import static org.rakam.util.JsonHelper.convert;

/**
 * Created by buremba <Burak Emre Kabakcı> on 02/02/15 14:30.
 */
@Singleton
@Path("/realtime")
public class RealTimeHttpService extends HttpService {
    private final ReportMetadataStore metastore;
    private final QueryExecutor executor;
    SqlParser sqlParser = new SqlParser();
    private final Duration slideInterval = Duration.ofSeconds(5);
    private final Duration window = Duration.ofSeconds(45);

    @Inject
    public RealTimeHttpService(ReportMetadataStore metastore, QueryExecutor executor) {
        this.metastore = checkNotNull(metastore, "metastore is null");
        this.executor = checkNotNull(executor, "executor is null");
    }

    /**
     * @api {post} /realtime/create Create realtime report
     * @apiVersion 0.1.0
     * @apiName CreateRealTimeReport
     * @apiGroup realtime
     * @apiDescription Creates realtime report using continuous queries.
     * This module adds a new attribute called 'time' to events, it's simply a unix epoch that represents the seconds the event is occurred.
     * Continuous query continuously aggregates 'time' column and
     * realtime module executes queries on continuous query table similar to 'select count from stream_count where time > now() - interval 5 second'
     * @apiError Project does not exist.
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {"success": true}
     * @apiParam {String} project   Project tracker code
     * @apiParam {String} name   The name of the report.
     * @apiParam {String=COUNT,SUM,MINIMUM,MAXIMUM,APPROXIMATE_UNIQUE,VARIANCE,POPULATION_VARIANCE,STANDARD_DEVIATION,AVERAGE} aggregation    The query that specifies collections that will be fetched
     * @apiParam {String} [measure]   The columns that will be used by aggregation function. If aggregation function is not COUNT, then this field is not optional.
     * @apiParam {String} [dimension]   The dimension of the realtime report. It groups the result-set based on the dimension field value.
     * @apiParam {String[]} [collections]  The collections that is the source of the report. Events that belong to these collections will be processed by the realtime report.
     * @apiParam {String} [filter] Optional SQL predicate expression that will filter the events. Referenced columns must be available all specified collections of the reports.
     * @apiParamExample {json} Request-Example:
     * {"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}
     * @apiSuccess (200) {Boolean} success Returns the success status.
     * @apiExample {curl} Example usage:
     * curl 'http://localhost:9999/realtime/create' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}'
     */
    @JsonRequest
    @POST
    @Path("/create")
    public JsonResponse create(RealTimeReport query) {
        String randTable = UUID.randomUUID().toString().substring(0, 8);

        String sqlQuery = new StringBuilder().append("select ")
                .append(createSelect(query.aggregation, query.measure, query.dimension))
                .append(format(", (time / %d) as time", slideInterval.getSeconds()))
                .append(" from stream")
                .append(query.filter == null ? "" : "where " + query.filter)
                .append(query.dimension != null ? "group by 1" : "").toString();

        ContinuousQuery report = new ContinuousQuery(query.project,
                query.name,
                randTable,
                sqlQuery,
                query.collections,
                ImmutableMap.of("type", "realtime", "report", query));
        metastore.createContinuousQuery(report);
        return new JsonResponse() {
            public final boolean success = true;
        };
    }

    /**
     * @api {post} /realtime/get Create realtime report
     * @apiVersion 0.1.0
     * @apiName GetRealTimeReport
     * @apiGroup realtime
     * @apiDescription Executes the query and returns data.
     * @apiError Project does not exist.
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {"success": true}
     * @apiParam {String} project   Project tracker code
     * @apiParam {String} name   The name of the report.
     * @apiParam [String] filter Predicate
     * @apiParam [Number] date an unix epoch value the query will be performed on.
     * @apiParamExample {json} Request-Example:
     * {"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}
     * @apiSuccess (200) {Boolean} success Returns the success status.
     * @apiExample {curl} Example usage:
     * curl 'http://localhost:9999/realtime/get' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}'
     */
    @JsonRequest
    @POST
    @Path("/get")
    public CompletableFuture get(RealTimeQuery query) {

        Expression expression;
        if (query.filter != null) {
            expression = sqlParser.createExpression(query.filter);
        } else {
            expression = null;
        }

        ContinuousQuery continuousQuery = metastore.getContinuousQuery(query.project, query.name);

        long epochSecond = query.date != null ? query.date : Instant.now().getEpochSecond();

        long currentWindow = epochSecond / 5;
        long previousWindow = (epochSecond - window.getSeconds()) / 5;
        String sqlQuery = format("select value from %s where %s %s",
                continuousQuery.tableName,
                format("time between %d and %d", previousWindow, currentWindow),
                expression == null ? "" : expression.toString());

        return executor.executeQuery(sqlQuery).getResult();
    }

    /**
     * @api {post} /realtime/list List realtime reports
     * @apiVersion 0.1.0
     * @apiName ListRealTimeReports
     * @apiGroup realtime
     * @apiDescription List the realtime reports created for the project.
     * @apiError Project does not exist.
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {"reports": [{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}]}
     * @apiParam {String} project   Project tracker code
     * @apiParamExample {json} Request-Example:
     * {"project": "projectId"}
     * @apiSuccess (200) {Object[]} reports Returns the list of realtime reports.
     * @apiParam {String} reports.project   Project tracker code
     * @apiParam {String} reports.name   The name of the report.
     * @apiParam {String=COUNT,SUM,MINIMUM,MAXIMUM,APPROXIMATE_UNIQUE,VARIANCE,POPULATION_VARIANCE,STANDARD_DEVIATION,AVERAGE} reports.aggregation    The query that specifies collections that will be fetched
     * @apiParam {String} [reports.measure]   The columns that will be used by aggregation function. If aggregation function is not COUNT, then this field is not optional.
     * @apiParam {String} [reports.dimension]   The dimension of the realtime report.
     * @apiParam {String[]} [reports.collections]  The collections that is the source of the report. Events that belong to these collections will be processed by the realtime report.
     * @apiParam {String} [reports.filter] Optional SQL predicate expression that will filter the events.
     * @apiExample {curl} Example usage:
     * curl 'http://localhost:9999/realtime/list' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId"}'
     */
    @JsonRequest
    @POST
    @Path("/list")
    public Object list(JsonNode json) {
        JsonNode project = json.get("project");
        if (project == null) {
            return errorMessage("project parameter is required", 400);
        }
        return metastore.getContinuousQueries(project.asText()).stream()
                .filter(report -> report.options != null && Objects.equals(report.options.get("type"), "realtime"))
                .map(report -> convert(report.options.get("report"), RealTimeReport.class))
                .collect(Collectors.toList());
    }


    /**
     * @api {post} /realtime/delete Delete realtime report
     * @apiVersion 0.1.0
     * @apiName DeleteRealTimeReport
     * @apiGroup realtime
     * @apiDescription Deletes specified realtime report.
     * @apiError Project does not exist.
     * @apiError Report does not exist.
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {"success": true}
     * @apiParam {String} project   Project tracker code
     * @apiParam {String} name   The name of the report that is specified when the is created.
     * @apiParamExample {json} Request-Example:
     * {"project": "projectId", "name": "Events by collection"}
     * @apiSuccess (200) {Boolean} success Returns the success status.
     * @apiExample {curl} Example usage:
     * curl 'http://localhost:9999/realtime/delete' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection"}'
     */
    @JsonRequest
    @POST
    @Path("/delete")
    public Object delete(JsonNode json) {
        JsonNode project = json.get("project");
        if (project == null || !project.isTextual()) {
            return errorMessage("project parameter is required", 400);
        }
        JsonNode name = json.get("name");
        if (name == null || !name.isTextual()) {
            return errorMessage("name parameter is required", 400);
        }

        // TODO: Check if it's a real-time report.
        metastore.deleteReport(project.asText(), name.asText());

        return JsonHelper.jsonObject().put("message", "successfully deleted");
    }

    /**
     * @api {post} /realtime/get Get realtime data
     * @apiVersion 0.1.0
     * @apiName GetRealTimeData
     * @apiGroup realtime
     * @apiDescription Creates realtime report using continuous queries.
     * This module adds a new attribute called 'time' to events, it's simply a unix epoch that represents the seconds the event is occurred.
     * Continuous query continuously aggregates 'time' column and
     * realtime module executes queries on continuous query table similar to 'select count from stream_count where time > now() - interval 5 second'
     * Another nice feature is that it's possible to get historical data by specifying the time period
     * @apiError Project does not exist.
     * @apiError User does not exist.
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {}
     * @apiParam {String} project   Project tracker code
     * @apiParam {String} name   The name of the realtime report.
     * @apiParamExample {json} Request-Example:
     * {"project": "projectId", "name": "Events by collection"}
     * @apiSuccess (200) {Object[]} result  Query result-set.
     * @apiExample {curl} Example usage:
     * curl 'http://localhost:9999/realtime/get' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection"}'
     */
    @JsonRequest
    @POST
    @Path("/get")
    public CompletableFuture get(JsonNode query) {
        String project = query.get("project").asText();
        String name = query.get("name").asText();
        return executor.executeQuery(format("select * from %s.%s where time > now() - interval '5' second ", project, name)).getResult();
    }

    public String createSelect(AggregationType aggType, String measure, String dimension) {

        if (measure == null) {
            if (aggType != AggregationType.COUNT)
                throw new IllegalArgumentException("either measure.expression or measure.field must be specified.");
        }

        StringBuilder builder = new StringBuilder();
        if (dimension != null)
            builder.append(" key, ");

        switch (aggType) {
            case AVERAGE:
                return builder.append("avg(key) as value").toString();
            case MAXIMUM:
                return builder.append("max(key) as value").toString();
            case MINIMUM:
                return builder.append("min(key) as value").toString();
            case COUNT:
                return builder.append("count(key) as value").toString();
            case SUM:
                return builder.append("sum(key) as value").toString();
            case APPROXIMATE_UNIQUE:
                return builder.append("approx_distinct(key) as value").toString();
            case VARIANCE:
                return builder.append("variance(key) as value").toString();
            case POPULATION_VARIANCE:
                return builder.append("variance(key) as value").toString();
            case STANDARD_DEVIATION:
                return builder.append("stddev(key) as value").toString();
            default:
                throw new IllegalArgumentException("aggregation type couldn't found.");
        }
    }

    public static class RealTimeQuery {
        public final String project;
        public final String name;
        public final String filter;
        public final Integer date;

        public RealTimeQuery(String project, String name, String filter, Integer date) {
            this.project = project;
            this.name = name;
            this.filter = filter;
            this.date = date;
        }
    }
}
