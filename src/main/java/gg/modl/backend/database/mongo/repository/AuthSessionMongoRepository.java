package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.AuthSessionDataFields;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthSessionMongoRepository {
    private final TenantMongoAccess tenantMongoAccess;

    public AuthSessionData saveForServer(Server server, AuthSessionData session) {
        return tenantMongoAccess.forServer(server).save(session, CollectionName.SESSIONS);
    }

    public AuthSessionData saveForGlobal(AuthSessionData session) {
        return tenantMongoAccess.global().save(session, CollectionName.SESSIONS);
    }

    public List<AuthSessionData> findActiveByEmail(Server server, String email, Date now) {
        return findActiveByEmail(tenantMongoAccess.forServer(server), EmailAddressUtil.normalize(email), now);
    }

    private List<AuthSessionData> findActiveByEmail(MongoTemplate template, String normalizedEmail, Date now) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(AuthSessionDataFields.EMAIL).is(normalizedEmail),
            Criteria.where(AuthSessionDataFields.EXPIRES_AT).gt(now)
        ));
        return template.find(query, AuthSessionData.class, CollectionName.SESSIONS);
    }

    public void deleteByEmail(Server server, String email) {
        deleteByEmailInternal(tenantMongoAccess.forServer(server), email);
    }

    private void deleteByEmailInternal(MongoTemplate template, String email) {
        String rawEmail = email == null ? null : email.trim();
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }

        String normalizedEmail = EmailAddressUtil.normalize(rawEmail);
        Query query = rawEmail.equals(normalizedEmail)
                      ? Query.query(Criteria.where(AuthSessionDataFields.EMAIL).is(normalizedEmail))
                      : Query.query(Criteria.where(AuthSessionDataFields.EMAIL).in(rawEmail, normalizedEmail));
        template.remove(query, AuthSessionData.class, CollectionName.SESSIONS);
    }

    public void deleteByEmailGlobal(String email) {
        deleteByEmailInternal(tenantMongoAccess.global(), email);
    }

    public Optional<AuthSessionData> findActiveById(Server server, String sessionId, Date now) {
        return findActiveById(tenantMongoAccess.forServer(server), sessionId, now);
    }

    private Optional<AuthSessionData> findActiveById(MongoTemplate template, String sessionId, Date now) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(AuthSessionDataFields.ID).is(sessionId),
            Criteria.where(AuthSessionDataFields.EXPIRES_AT).gt(now)
        ));
        return Optional.ofNullable(template.findOne(query, AuthSessionData.class, CollectionName.SESSIONS));
    }

    public Optional<AuthSessionData> findActiveByIdGlobal(String sessionId, Date now) {
        return findActiveById(tenantMongoAccess.global(), sessionId, now);
    }

    public Optional<AuthSessionData> findAndRefreshById(Server server, String sessionId, Date now, Date newExpiresAt) {
        return findAndRefreshById(tenantMongoAccess.forServer(server), sessionId, now, newExpiresAt);
    }

    public Optional<AuthSessionData> findAndRefreshByIdGlobal(String sessionId, Date now, Date newExpiresAt) {
        return findAndRefreshById(tenantMongoAccess.global(), sessionId, now, newExpiresAt);
    }

    private Optional<AuthSessionData> findAndRefreshById(MongoTemplate template, String sessionId, Date now, Date newExpiresAt) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(AuthSessionDataFields.ID).is(sessionId),
            Criteria.where(AuthSessionDataFields.EXPIRES_AT).gt(now)
        ));
        Update update = new Update().set(AuthSessionDataFields.EXPIRES_AT, newExpiresAt);
        return Optional.ofNullable(template.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), AuthSessionData.class, CollectionName.SESSIONS
        ));
    }

    public boolean refreshExpiresAt(Server server, String sessionId, Date expiresAt) {
        return refreshExpiresAt(tenantMongoAccess.forServer(server), sessionId, expiresAt);
    }

    private boolean refreshExpiresAt(MongoTemplate template, String sessionId, Date expiresAt) {
        Update update = new Update().set(AuthSessionDataFields.EXPIRES_AT, expiresAt);
        return template.updateFirst(idQuery(sessionId), update, AuthSessionData.class, CollectionName.SESSIONS).getMatchedCount() > 0;
    }

    private Query idQuery(String sessionId) {
        return Query.query(Criteria.where(AuthSessionDataFields.ID).is(sessionId));
    }

    public boolean refreshExpiresAtGlobal(String sessionId, Date expiresAt) {
        return refreshExpiresAt(tenantMongoAccess.global(), sessionId, expiresAt);
    }

    public void deleteById(Server server, String sessionId) {
        tenantMongoAccess.forServer(server).remove(idQuery(sessionId), AuthSessionData.class, CollectionName.SESSIONS);
    }

    public void deleteByIdGlobal(String sessionId) {
        tenantMongoAccess.global().remove(idQuery(sessionId), AuthSessionData.class, CollectionName.SESSIONS);
    }
}
