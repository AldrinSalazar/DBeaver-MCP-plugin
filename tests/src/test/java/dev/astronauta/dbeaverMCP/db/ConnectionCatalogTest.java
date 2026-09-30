package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.junit.Test;

public class ConnectionCatalogTest {
    private static DBPDataSourceContainer connection(String id, String displayName) {
        return DbFakes.proxy(DBPDataSourceContainer.class, (name, args) -> switch (name) {
            case "getId" -> id;
            case "getName" -> displayName;
            default -> DbFakes.unexpected(name);
        });
    }

    private static ConnectionCatalog catalog(DBPDataSourceContainer... connections) {
        var registry = DbFakes.proxy(DBPDataSourceRegistry.class, (name, args) ->
            name.equals("getDataSources") ? List.of(connections) : DbFakes.unexpected(name));
        var project = DbFakes.proxy(DBPProject.class, (name, args) ->
            name.equals("getDataSourceRegistry") ? registry : DbFakes.unexpected(name));
        return new ConnectionCatalog(() -> List.of(project));
    }

    @Test
    public void hiddenDuplicateNameDoesNotCreateAmbiguity() throws Exception {
        var exposed = connection("exposed", "Database");
        var hidden = connection("hidden", "Database");
        AccessPolicy policy = new AccessPolicy(AccessMode.READ_ONLY, Set.of(), Set.of("exposed"), Set.of());
        assertSame(exposed, catalog(exposed, hidden).resolveConnection("database", policy));
    }

    @Test
    public void hiddenIdsCannotBeResolved() {
        AccessPolicy policy = new AccessPolicy(AccessMode.READ_ONLY, Set.of(), Set.of(), Set.of());
        BridgeException failure = assertThrows(BridgeException.class,
            () -> catalog(connection("hidden", "Secret name")).resolveConnection("hidden", policy));
        assertFalse(failure.getMessage().contains("Secret name"));
    }
}
