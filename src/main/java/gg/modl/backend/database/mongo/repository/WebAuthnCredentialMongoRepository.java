package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.WebAuthnCredentialFields;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class WebAuthnCredentialMongoRepository extends AbstractServerMongoRepository<WebAuthnCredential> {
    public WebAuthnCredentialMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(WebAuthnCredential.class, CollectionName.WEBAUTHN_CREDENTIALS, tenantMongoAccess);
    }

    public boolean existsByEmail(Server server, String email) {
        return exists(server, emailQuery(email));
    }

    private Query emailQuery(String email) {
        return Query.query(Criteria.where(WebAuthnCredentialFields.EMAIL)
            .is(EmailAddressUtil.normalize(email)));
    }

    public List<WebAuthnCredential> findByEmail(Server server, String email) {
        return find(server, emailQuery(email));
    }

    public Optional<WebAuthnCredential> findByCredentialId(Server server, String credentialId) {
        return findOne(server, Query.query(Criteria.where(WebAuthnCredentialFields.CREDENTIAL_ID).is(credentialId)));
    }

    public List<WebAuthnCredential> findAllByCredentialId(Server server, String credentialId) {
        return find(server, Query.query(Criteria.where(WebAuthnCredentialFields.CREDENTIAL_ID).is(credentialId)));
    }

    public Optional<WebAuthnCredential> findByUserHandle(Server server, String userHandle) {
        return findOne(server, Query.query(Criteria.where(WebAuthnCredentialFields.USER_HANDLE).is(userHandle)));
    }

    public boolean updateUsage(Server server, String credentialId, long signatureCount, Date lastUsedAt) {
        Update update = new Update()
            .set(WebAuthnCredentialFields.SIGNATURE_COUNT, signatureCount)
            .set(WebAuthnCredentialFields.LAST_USED_AT, lastUsedAt);
        return updateFirst(server, Query.query(Criteria.where(WebAuthnCredentialFields.CREDENTIAL_ID).is(credentialId)), update)
                   .getMatchedCount() > 0;
    }

    public boolean renameByIdAndEmail(Server server, String credentialMongoId, String email, String newName) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(WebAuthnCredentialFields.ID).is(credentialMongoId),
            Criteria.where(WebAuthnCredentialFields.EMAIL).is(EmailAddressUtil.normalize(email))
        ));
        return updateFirst(server, query, new Update().set(WebAuthnCredentialFields.NAME, newName.trim()))
                   .getModifiedCount() > 0;
    }

    public boolean deleteByIdAndEmail(Server server, String credentialMongoId, String email) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(WebAuthnCredentialFields.ID).is(credentialMongoId),
            Criteria.where(WebAuthnCredentialFields.EMAIL).is(EmailAddressUtil.normalize(email))
        ));
        return remove(server, query).getDeletedCount() > 0;
    }
}
