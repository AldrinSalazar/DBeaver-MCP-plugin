package dev.astronauta.dbeaverMCP.db;

import java.sql.Connection;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns an MCP session and its physical context; never uses the container's transaction manager. */
final class IsolatedSession implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(IsolatedSession.class);

    enum CommitOutcome {
        AUTO_COMMIT("auto-commit"), COMMITTED("committed"), NON_TRANSACTIONAL("non-transactional");

        private final String id;

        CommitOutcome(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    private final DBCExecutionContext context;
    private final DBCSession session;
    private final Connection jdbc;
    private final DBCTransactionManager transactions;

    private IsolatedSession(DBCExecutionContext context, DBCSession session) throws Exception {
        this.context = context;
        this.session = session;
        this.jdbc = session instanceof JDBCSession jdbcSession ? jdbcSession.getOriginal() : null;
        DBCTransactionManager manager = DBUtils.getAdapter(DBCTransactionManager.class, session);
        this.transactions = manager != null ? manager : DBUtils.getAdapter(DBCTransactionManager.class, context);
    }

    // Tests supply session-owned handles without requiring a running DBeaver workspace.
    IsolatedSession(DBCSession session, Connection jdbc, DBCTransactionManager transactions) {
        this.context = null;
        this.session = session;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    static IsolatedSession open(DBPDataSource dataSource, String purpose) throws Exception {
        DBCExecutionContext context = dataSource.getDefaultInstance()
            .openIsolatedContext(new VoidProgressMonitor(), purpose, null);
        DBCSession session = null;
        try {
            session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.USER, purpose);
            return new IsolatedSession(context, session);
        } catch (Exception e) {
            closeResource(session);
            closeResource(context);
            throw e;
        }
    }

    DBCSession session() {
        return session;
    }

    Connection requireJdbc() throws Exception {
        if (jdbc == null || jdbc.isClosed()) {
            throw new BridgeException("Cannot establish read-only protection: no usable JDBC connection."
                + " SQL was not executed.");
        }
        return jdbc;
    }

    SqlGuard.Dialect dialect() throws Exception {
        return SqlGuard.Dialect.forProduct(jdbc == null ? null : jdbc.getMetaData().getDatabaseProductName());
    }

    void requireTransactionHandling() throws BridgeException {
        if (jdbc == null && transactions == null) {
            throw new BridgeException("Cannot determine the isolated session's transaction handling."
                + " SQL was not executed.");
        }
    }

    CommitOutcome commit() throws BridgeException {
        try {
            if (transactions == null) {
                if (jdbc.getAutoCommit()) {
                    return CommitOutcome.AUTO_COMMIT;
                }
                jdbc.commit();
                return CommitOutcome.COMMITTED;
            }
            if (!transactions.isSupportsTransactions()) {
                return CommitOutcome.NON_TRANSACTIONAL;
            }
            if (transactions.isAutoCommit()) {
                return CommitOutcome.AUTO_COMMIT;
            }
            transactions.commit(session);
            return CommitOutcome.COMMITTED;
        } catch (Exception e) {
            throw new BridgeException("Commit failed; transaction outcome is unknown: " + e.getMessage(), e);
        }
    }

    void rollbackAfterFailure(Exception failure) {
        try {
            if (transactions != null) {
                if (transactions.isSupportsTransactions() && !transactions.isAutoCommit()) {
                    transactions.rollback(session, null);
                }
            } else if (jdbc != null && !jdbc.getAutoCommit()) {
                jdbc.rollback();
            }
        } catch (Exception e) {
            failure.addSuppressed(e);
            LOG.error("MCP rollback failed", e);
        }
    }

    @Override
    public void close() {
        closeResource(session);
        closeResource(context);
    }

    private static void closeResource(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            LOG.error("Failed to close isolated database resource", e);
        }
    }
}
