package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.admin.data.AdminUser;
import gg.modl.backend.database.mongo.AbstractGlobalMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.AdminUserFields;
import gg.modl.backend.email.EmailAddressUtil;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserMongoRepository extends AbstractGlobalMongoRepository<AdminUser> {
    private static final String COLLECTION_NAME = "admin_users";

    public AdminUserMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(AdminUser.class, COLLECTION_NAME, tenantMongoAccess);
    }

    public Optional<AdminUser> findByEmailIgnoreCase(String email) {
        String normalizedEmail = Objects.requireNonNullElse(EmailAddressUtil.normalize(email), "");
        Query query = Query.query(Criteria.where(AdminUserFields.EMAIL).is(normalizedEmail));
        return findOne(query);
    }

    public void updateLastActivity(String email, String clientIp, Date lastActivityAt) {
        String normalizedEmail = Objects.requireNonNullElse(EmailAddressUtil.normalize(email), "");
        Query query = Query.query(Criteria.where(AdminUserFields.EMAIL).is(normalizedEmail));
        Update update = new Update()
            .set(AdminUserFields.LAST_ACTIVITY_AT, lastActivityAt)
            .addToSet(AdminUserFields.LOGGED_IN_IPS, clientIp);
        updateFirst(query, update);
    }
}
