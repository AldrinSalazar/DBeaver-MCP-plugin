package dev.astronauta.dbeaverMCP.db;

import java.lang.reflect.Proxy;
import java.util.List;

import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCResultSetMetaData;

final class DbFakes {
    @FunctionalInterface
    interface Calls {
        Object call(String name, Object[] arguments) throws Throwable;
    }

    private DbFakes() {
    }

    static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (object, method, arguments) -> switch (method.getName()) {
                case "toString" -> type.getSimpleName() + " fake";
                case "hashCode" -> System.identityHashCode(object);
                case "equals" -> object == arguments[0];
                default -> calls.call(method.getName(), arguments);
            }));
    }

    static Object unexpected(String name) {
        throw new AssertionError("Unexpected call: " + name);
    }

    static DBCResultSet rows(Object... values) {
        DBCAttributeMetaData column = proxy(DBCAttributeMetaData.class, (name, args) -> switch (name) {
            case "getName", "getLabel" -> "value";
            case "getTypeName" -> "text";
            default -> unexpected(name);
        });
        DBCResultSetMetaData metadata = () -> List.of(column);
        int[] position = {-1};
        return proxy(DBCResultSet.class, (name, args) -> switch (name) {
            case "getMeta" -> metadata;
            case "nextRow" -> ++position[0] < values.length;
            case "getAttributeValue" -> values[position[0]];
            case "close" -> null;
            default -> unexpected(name);
        });
    }
}
