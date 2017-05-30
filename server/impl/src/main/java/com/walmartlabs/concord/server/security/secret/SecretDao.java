package com.walmartlabs.concord.server.security.secret;

import com.walmartlabs.concord.common.db.AbstractDao;
import com.walmartlabs.concord.server.api.security.secret.SecretEntry;
import com.walmartlabs.concord.server.api.security.secret.SecretType;
import com.walmartlabs.concord.server.user.UserPermissionCleaner;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

import static com.walmartlabs.concord.server.jooq.public_.tables.Secrets.SECRETS;

@Named
public class SecretDao extends AbstractDao {

    private final UserPermissionCleaner permissionCleaner;

    @Inject
    public SecretDao(Configuration cfg, UserPermissionCleaner permissionCleaner) {
        super(cfg);
        this.permissionCleaner = permissionCleaner;
    }

    public void insert(String name, SecretType type, byte[] data) {
        tx(tx -> insert(tx, name, type, data));
    }

    public void insert(DSLContext tx, String name, SecretType type, byte[] data) {
        tx.insertInto(SECRETS)
                .columns(SECRETS.SECRET_NAME, SECRETS.SECRET_TYPE, SECRETS.SECRET_DATA)
                .values(name, type.toString(), data)
                .execute();
    }

    public SecretDataEntry get(String name) {
        try (DSLContext tx = DSL.using(cfg)) {
            return selectSecretDataEntry(tx)
                    .where(SECRETS.SECRET_NAME.eq(name))
                    .fetchOne(SecretDao::toDataEntry);
        }
    }

    public List<SecretEntry> list(Field<?> sortField, boolean asc) {
        try (DSLContext tx = DSL.using(cfg)) {
            SelectJoinStep<Record2<String, String>> query = tx.select(SECRETS.SECRET_NAME, SECRETS.SECRET_TYPE)
                    .from(SECRETS);

            if (sortField != null) {
                query.orderBy(asc ? sortField.asc() : sortField.desc());
            }

            return query.fetch(r -> new SecretEntry(r.get(SECRETS.SECRET_NAME),
                    SecretType.valueOf(r.get(SECRETS.SECRET_TYPE))));
        }
    }

    public void delete(String name) {
        tx(tx -> {
            permissionCleaner.onSecretRemoval(tx, name);
            tx.deleteFrom(SECRETS)
                    .where(SECRETS.SECRET_NAME.eq(name))
                    .execute();
        });
    }

    public boolean exists(String name) {
        try (DSLContext tx = DSL.using(cfg)) {
            return tx.fetchExists(tx.selectFrom(SECRETS)
                    .where(SECRETS.SECRET_NAME.eq(name)));
        }
    }

    private static SelectJoinStep<Record3<String, String, byte[]>> selectSecretDataEntry(DSLContext tx) {
        return tx.select(SECRETS.SECRET_NAME, SECRETS.SECRET_TYPE, SECRETS.SECRET_DATA)
                .from(SECRETS);
    }

    private static SecretDataEntry toDataEntry(Record3<String, String, byte[]> r) {
        return new SecretDataEntry(r.get(SECRETS.SECRET_NAME), SecretType.valueOf(r.get(SECRETS.SECRET_TYPE)), r.get(SECRETS.SECRET_DATA));
    }

    public static class SecretDataEntry extends SecretEntry {

        private final byte[] data;

        public SecretDataEntry(String name, SecretType type, byte[] data) {
            super(name, type);
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }
    }
}
