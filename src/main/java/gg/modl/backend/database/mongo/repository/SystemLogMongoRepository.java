package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.admin.data.SystemLog;
import gg.modl.backend.database.mongo.AbstractGlobalMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.SystemLogFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class SystemLogMongoRepository extends AbstractGlobalMongoRepository<SystemLog> {
    public static final String COLLECTION_NAME = "system_logs";

    private static final String ALIAS_DATE = "date";
    private static final String ALIAS_LEVEL = "level";
    private static final String ALIAS_COUNT = "count";
    private static final String ALIAS_LEVELS = "levels";
    private static final String ALIAS_TOTAL = "total";

    private static final String SORT_LEVEL = "level";
    private static final String SORT_SOURCE = "source";
    private static final String SORT_CATEGORY = "category";
    private static final String SORT_RESOLVED = "resolved";
    private static final String SORT_TIMESTAMP = "timestamp";
    private static final String ORDER_DESC = "desc";
    private static final String RESOLVED_TRUE = "true";

    public SystemLogMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(SystemLog.class, COLLECTION_NAME, tenantMongoAccess);
    }

    public long countAll() {
        return count(new Query());
    }

    public long countSince(Date startDate) {
        return count(Query.query(Criteria.where(SystemLogFields.TIMESTAMP).gte(startDate)));
    }

    public long countByLevelSince(String level, Date startDate) {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(SystemLogFields.LEVEL).is(level),
            Criteria.where(SystemLogFields.TIMESTAMP).gte(startDate)
        )));
    }

    public long countUnresolvedByLevel(String level) {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(SystemLogFields.LEVEL).is(level),
            Criteria.where(SystemLogFields.RESOLVED).is(false)
        )));
    }

    public long countUnresolvedByLevelSince(String level, Date startDate) {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(SystemLogFields.LEVEL).is(level),
            Criteria.where(SystemLogFields.RESOLVED).is(false),
            Criteria.where(SystemLogFields.TIMESTAMP).gte(startDate)
        )));
    }

    public List<SystemLog> findLogs(
        String level,
        String source,
        String serverId,
        String category,
        String resolved,
        String search,
        Date startDate,
        Date endDate,
        String sort,
        String order,
        int skip,
        int limit
    ) {
        Query query = buildLogsQuery(level, source, serverId, category, resolved, search, startDate, endDate);
        query.with(Sort.by(resolveDirection(order), resolveSortField(sort)));
        query.skip(skip).limit(limit);
        return find(query);
    }

    private Query buildLogsQuery(
        String level,
        String source,
        String serverId,
        String category,
        String resolved,
        String search,
        Date startDate,
        Date endDate
    ) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (hasText(level)) {
            criteriaList.add(Criteria.where(SystemLogFields.LEVEL).is(level));
        }
        if (hasText(source)) {
            criteriaList.add(Criteria.where(SystemLogFields.SOURCE).is(source));
        }
        if (hasText(serverId)) {
            criteriaList.add(Criteria.where(SystemLogFields.SERVER_ID).is(serverId));
        }
        if (hasText(category)) {
            criteriaList.add(Criteria.where(SystemLogFields.CATEGORY).is(category));
        }
        if (resolved != null) {
            criteriaList.add(Criteria.where(SystemLogFields.RESOLVED).is(RESOLVED_TRUE.equals(resolved)));
        }
        if (hasText(search)) {
            criteriaList.add(Criteria.where(SystemLogFields.MESSAGE).regex(Pattern.quote(search), "i"));
        }
        if (startDate != null || endDate != null) {
            Criteria dateCriteria = Criteria.where(SystemLogFields.TIMESTAMP);
            if (startDate != null) {
                dateCriteria = dateCriteria.gte(startDate);
            }
            if (endDate != null) {
                dateCriteria = dateCriteria.lte(endDate);
            }
            criteriaList.add(dateCriteria);
        }
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
        return query;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Sort.Direction resolveDirection(String order) {
        return ORDER_DESC.equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    private String resolveSortField(String sort) {
        if (sort == null || sort.isBlank()) {
            return SystemLogFields.TIMESTAMP;
        }
        return switch (sort) {
            case SORT_LEVEL -> SystemLogFields.LEVEL;
            case SORT_SOURCE -> SystemLogFields.SOURCE;
            case SORT_CATEGORY -> SystemLogFields.CATEGORY;
            case SORT_RESOLVED -> SystemLogFields.RESOLVED;
            case SORT_TIMESTAMP -> SystemLogFields.TIMESTAMP;
            default -> SystemLogFields.TIMESTAMP;
        };
    }

    public long countLogs(
        String level,
        String source,
        String serverId,
        String category,
        String resolved,
        String search,
        Date startDate,
        Date endDate
    ) {
        return count(buildLogsQuery(level, source, serverId, category, resolved, search, startDate, endDate));
    }

    public List<String> findDistinctSources() {
        return globalTemplate().findDistinct(new Query(), SystemLogFields.SOURCE, COLLECTION_NAME, String.class);
    }

    public List<String> findDistinctCategories() {
        return globalTemplate().findDistinct(new Query(), SystemLogFields.CATEGORY, COLLECTION_NAME, String.class);
    }

    public SystemLog resolveById(String id, String resolvedBy, Date resolvedAt) {
        Query query = Query.query(Criteria.where(SystemLogFields.ID).is(id));
        Update update = new Update()
            .set(SystemLogFields.RESOLVED, true)
            .set(SystemLogFields.RESOLVED_BY, resolvedBy)
            .set(SystemLogFields.RESOLVED_AT, resolvedAt);
        return findAndModify(query, update, FindAndModifyOptions.options().returnNew(true));
    }

    public long deleteByIds(List<String> logIds) {
        return remove(Query.query(Criteria.where(SystemLogFields.ID).in(logIds))).getDeletedCount();
    }

    public long deleteAllLogs() {
        return remove(new Query()).getDeletedCount();
    }

    public List<SystemLog> findLogsForExport(
        String level,
        String source,
        String serverId,
        String category,
        String resolved,
        String search,
        Date startDate,
        Date endDate,
        int limit
    ) {
        Query query = buildLogsQuery(level, source, serverId, category, resolved, search, startDate, endDate);
        query.with(Sort.by(Sort.Direction.DESC, SystemLogFields.TIMESTAMP));
        query.limit(limit);
        return find(query);
    }

    public MonitoringLogStats aggregateMonitoringLogStats(Date oneDayAgo) {
        Document facet = new Document()
            .append("critical24h", List.of(
                new Document("$match", new Document(SystemLogFields.LEVEL, "critical")
                    .append(SystemLogFields.TIMESTAMP, new Document("$gte", oneDayAgo))),
                new Document("$count", "n")
            ))
            .append("error24h", List.of(
                new Document("$match", new Document(SystemLogFields.LEVEL, "error")
                    .append(SystemLogFields.TIMESTAMP, new Document("$gte", oneDayAgo))),
                new Document("$count", "n")
            ))
            .append("warning24h", List.of(
                new Document("$match", new Document(SystemLogFields.LEVEL, "warning")
                    .append(SystemLogFields.TIMESTAMP, new Document("$gte", oneDayAgo))),
                new Document("$count", "n")
            ))
            .append("total24h", List.of(
                new Document("$match", new Document(SystemLogFields.TIMESTAMP, new Document("$gte", oneDayAgo))),
                new Document("$count", "n")
            ))
            .append("unresolvedCritical", List.of(
                new Document("$match", new Document(SystemLogFields.LEVEL, "critical")
                    .append(SystemLogFields.RESOLVED, false)),
                new Document("$count", "n")
            ))
            .append("unresolvedError", List.of(
                new Document("$match", new Document(SystemLogFields.LEVEL, "error")
                    .append(SystemLogFields.RESOLVED, false)),
                new Document("$count", "n")
            ));

        List<Document> pipeline = List.of(new Document("$facet", facet));
        List<Document> results = globalTemplate().getCollection(COLLECTION_NAME)
            .aggregate(pipeline)
            .into(new ArrayList<>());

        if (results.isEmpty()) {
            return new MonitoringLogStats(0, 0, 0, 0, 0, 0);
        }

        Document doc = results.get(0);
        return new MonitoringLogStats(
            extractFacetCount(doc, "critical24h"),
            extractFacetCount(doc, "error24h"),
            extractFacetCount(doc, "warning24h"),
            extractFacetCount(doc, "total24h"),
            extractFacetCount(doc, "unresolvedCritical"),
            extractFacetCount(doc, "unresolvedError")
        );
    }

    private long extractFacetCount(Document facets, String key) {
        List<?> list = facets.getList(key, Document.class, List.of());
        if (list.isEmpty()) {
            return 0;
        }
        Object first = list.getFirst();
        if (first instanceof Document doc) {
            Number n = doc.get("n", Number.class);
            return n != null ? n.longValue() : 0;
        }
        return 0;
    }

    public record MonitoringLogStats(long critical24h, long error24h, long warning24h,
                                      long total24h, long unresolvedCritical, long unresolvedError) {}

    public List<Document> findLogTrends(Date startDate) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(SystemLogFields.TIMESTAMP).gte(startDate)),
            Aggregation.project()
                .and(DateOperators.DateToString.dateOf(SystemLogFields.TIMESTAMP).toString("%Y-%m-%d")).as(ALIAS_DATE)
                .and(SystemLogFields.LEVEL).as(ALIAS_LEVEL),
            Aggregation.group(ALIAS_DATE, ALIAS_LEVEL).count().as(ALIAS_COUNT),
            Aggregation.group("_id." + ALIAS_DATE)
                .push(new Document(ALIAS_LEVEL, "$_id." + ALIAS_LEVEL).append(ALIAS_COUNT, "$" + ALIAS_COUNT)).as(ALIAS_LEVELS)
                .sum(ALIAS_COUNT).as(ALIAS_TOTAL),
            Aggregation.sort(Sort.Direction.ASC, "_id")
        );
        return aggregate(aggregation, Document.class).getMappedResults();
    }
}

