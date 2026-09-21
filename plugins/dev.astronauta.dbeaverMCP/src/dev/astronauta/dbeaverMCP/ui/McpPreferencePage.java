package dev.astronauta.dbeaverMCP.ui;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.astronauta.dbeaverMCP.McpPlugin;
import dev.astronauta.dbeaverMCP.McpPreferences;
import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.db.DBeaverBridge;
import dev.astronauta.dbeaverMCP.db.DBeaverBridge.ConnectionEntry;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import dev.astronauta.dbeaverMCP.server.McpServerManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Window > Preferences > MCP Server.
 * Server lifecycle, SQL access mode, and per-connection grants.
 */
public class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enabledCheck;
    private Button autoStartCheck;
    private Text portText;
    private Text hostText;
    private Text tokenText;
    private Text timeoutText;
    private Label statusLabel;
    private Button authCheck;
    private Label authWarning;
    private Label hostWarning;
    private Button regenerateButton;
    private Button copyButton;

    private Button metadataModeRadio;
    private Button readModeRadio;
    private Button writeModeRadio;
    private Label writeWarning;

    private TableViewer connectionsViewer;
    private Group grantsGroup;
    private Button metadataCheck;
    private Button readCheck;
    private Button writeCheck;

    private List<ConnectionEntry> allConnections = new ArrayList<>();
    private Set<String> metadataIds = new LinkedHashSet<>();
    private Set<String> readIds = new LinkedHashSet<>();
    private Set<String> writeIds = new LinkedHashSet<>();
    private ConnectionEntry selected;

    public McpPreferencePage() {
    }

    @Override
    public void init(IWorkbench workbench) {
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(8, 8).spacing(8, 8).applyTo(root);

        createServerGroup(root);
        createModeGroup(root);
        createConnectionsGroup(root);

        loadValues();
        updateStatus();
        return root;
    }

    private void createServerGroup(Composite root) {
        Group group = new Group(root, SWT.NONE);
        group.setText("Server");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
        GridLayoutFactory.fillDefaults().numColumns(4).margins(8, 8).spacing(8, 6).applyTo(group);

        enabledCheck = new Button(group, SWT.CHECK);
        enabledCheck.setText("Enable MCP server");
        GridDataFactory.fillDefaults().span(4, 1).applyTo(enabledCheck);

        autoStartCheck = new Button(group, SWT.CHECK);
        autoStartCheck.setText("Start automatically with DBeaver");
        GridDataFactory.fillDefaults().span(4, 1).applyTo(autoStartCheck);

        Label portLabel = new Label(group, SWT.NONE);
        portLabel.setText("Port:");
        portText = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().hint(80, SWT.DEFAULT).applyTo(portText);
        Label hostLabel = new Label(group, SWT.NONE);
        hostLabel.setText("Listen host:");
        hostText = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(hostText);

        hostWarning = new Label(group, SWT.WRAP);
        hostWarning.setText("Warning: listening on a non-loopback address exposes the MCP server beyond this machine.");
        hostWarning.setForeground(hostWarning.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridDataFactory.fillDefaults().span(4, 1).grab(true, false).applyTo(hostWarning);
        hostText.addListener(SWT.Modify, e -> updateHostWarning());

        Label timeoutLabel = new Label(group, SWT.NONE);
        timeoutLabel.setText("Query timeout (seconds, 0 = off):");
        timeoutText = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().hint(80, SWT.DEFAULT).applyTo(timeoutText);
        Label timeoutFiller = new Label(group, SWT.NONE);
        GridDataFactory.fillDefaults().span(2, 1).applyTo(timeoutFiller);

        authCheck = new Button(group, SWT.CHECK);
        authCheck.setText("Require bearer token (recommended)");
        GridDataFactory.fillDefaults().span(4, 1).applyTo(authCheck);
        authCheck.addListener(SWT.Selection, e -> updateAuthState());

        authWarning = new Label(group, SWT.WRAP);
        authWarning.setText("Warning: with authentication disabled, anyone able to reach the server can use it.");
        authWarning.setForeground(authWarning.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridDataFactory.fillDefaults().span(4, 1).grab(true, false).applyTo(authWarning);

        Label tokenLabel = new Label(group, SWT.NONE);
        tokenLabel.setText("Bearer token:");
        tokenText = new Text(group, SWT.BORDER | SWT.SINGLE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(tokenText);
        regenerateButton = new Button(group, SWT.PUSH);
        regenerateButton.setText("Regenerate");
        regenerateButton.addListener(SWT.Selection, e -> tokenText.setText(randomToken()));
        copyButton = new Button(group, SWT.PUSH);
        copyButton.setText("Copy");
        copyButton.addListener(SWT.Selection, e -> copyTokenToClipboard());

        Label hint = new Label(group, SWT.WRAP);
        hint.setText("MCP clients must send the token as 'Authorization: Bearer <token>'.");
        GridDataFactory.fillDefaults().span(4, 1).grab(true, false).applyTo(hint);

        Composite buttons = new Composite(group, SWT.NONE);
        GridDataFactory.fillDefaults().span(4, 1).applyTo(buttons);
        GridLayoutFactory.fillDefaults().numColumns(3).applyTo(buttons);
        Button startButton = new Button(buttons, SWT.PUSH);
        startButton.setText("Start now");
        startButton.addListener(SWT.Selection, e -> {
            saveValues();
            try {
                McpServerManager.getInstance().start();
            } catch (Exception ex) {
                McpPlugin.logError("Failed to start MCP server", ex);
            }
            updateStatus();
        });
        Button stopButton = new Button(buttons, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.addListener(SWT.Selection, e -> {
            McpServerManager.getInstance().stop();
            updateStatus();
        });
        statusLabel = new Label(buttons, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusLabel);
    }

    private void createModeGroup(Composite root) {
        Group group = new Group(root, SWT.NONE);
        group.setText("MCP SQL access");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
        GridLayoutFactory.fillDefaults().margins(8, 8).spacing(8, 4).applyTo(group);

        metadataModeRadio = new Button(group, SWT.RADIO);
        metadataModeRadio.setText("Metadata only (browse databases, no SQL execution)");
        readModeRadio = new Button(group, SWT.RADIO);
        readModeRadio.setText("Read only (query_sql with read-only protections)");
        writeModeRadio = new Button(group, SWT.RADIO);
        writeModeRadio.setText("Read / write (also exposes execute_sql)");

        writeWarning = new Label(group, SWT.WRAP);
        writeWarning.setText("Warning: read/write mode allows MCP clients holding the token"
            + " to modify data in connections with Write queries enabled.");
        writeWarning.setForeground(writeWarning.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(writeWarning);

        Listener modeListener = e -> updateGrantEnablement();
        metadataModeRadio.addListener(SWT.Selection, modeListener);
        readModeRadio.addListener(SWT.Selection, modeListener);
        writeModeRadio.addListener(SWT.Selection, modeListener);
    }

    private void createConnectionsGroup(Composite root) {
        Group group = new Group(root, SWT.NONE);
        group.setText("Connections");
        GridDataFactory.fillDefaults().grab(true, true).applyTo(group);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(8, 8).spacing(8, 6).applyTo(group);

        Label info = new Label(group, SWT.WRAP);
        info.setText("Select a connection to grant MCP access. Connections without any grant are invisible"
            + " to MCP clients. The plugin reuses the connections stored in DBeaver, it never asks for credentials.");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(info);

        Table table = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 150).applyTo(table);
        String[] titles = {"Connection", "Project", "Driver", "Read-only protection", "Granted access"};
        int[] widths = {180, 120, 140, 140, 170};
        for (int i = 0; i < titles.length; i++) {
            TableColumn column = new TableColumn(table, SWT.NONE);
            column.setText(titles[i]);
            column.setWidth(widths[i]);
        }
        connectionsViewer = new TableViewer(table);
        connectionsViewer.setContentProvider(ArrayContentProvider.getInstance());
        connectionsViewer.setLabelProvider(new ConnectionLabelProvider());
        connectionsViewer.addSelectionChangedListener(e -> {
            IStructuredSelection selection = e.getStructuredSelection();
            selected = selection.getFirstElement() instanceof ConnectionEntry entry ? entry : null;
            updateGrantChecks();
        });

        Composite side = new Composite(group, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(side);
        GridLayoutFactory.fillDefaults().applyTo(side);
        Button enableAll = new Button(side, SWT.PUSH);
        enableAll.setText("Enable all");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(enableAll);
        enableAll.addListener(SWT.Selection, e -> {
            for (ConnectionEntry entry : allConnections) {
                metadataIds.add(entry.id());
                readIds.add(entry.id());
                writeIds.add(entry.id());
            }
            updateGrantChecks();
            connectionsViewer.refresh();
        });
        Button disableAll = new Button(side, SWT.PUSH);
        disableAll.setText("Disable all");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(disableAll);
        disableAll.addListener(SWT.Selection, e -> {
            metadataIds.clear();
            readIds.clear();
            writeIds.clear();
            updateGrantChecks();
            connectionsViewer.refresh();
        });
        Button refresh = new Button(side, SWT.PUSH);
        refresh.setText("Refresh");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(refresh);
        refresh.addListener(SWT.Selection, e -> refreshConnections());

        grantsGroup = new Group(group, SWT.NONE);
        grantsGroup.setText("Access");
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(grantsGroup);
        GridLayoutFactory.fillDefaults().numColumns(3).margins(8, 8).applyTo(grantsGroup);
        metadataCheck = new Button(grantsGroup, SWT.CHECK);
        metadataCheck.setText("Metadata access");
        readCheck = new Button(grantsGroup, SWT.CHECK);
        readCheck.setText("Read-only queries");
        writeCheck = new Button(grantsGroup, SWT.CHECK);
        writeCheck.setText("Write queries");
        metadataCheck.addListener(SWT.Selection, e -> toggleGrant(metadataIds, metadataCheck.getSelection()));
        readCheck.addListener(SWT.Selection, e -> toggleGrant(readIds, readCheck.getSelection()));
        writeCheck.addListener(SWT.Selection, e -> toggleGrant(writeIds, writeCheck.getSelection()));
    }

    private AccessMode selectedMode() {
        if (writeModeRadio.getSelection()) {
            return AccessMode.READ_WRITE;
        }
        if (metadataModeRadio.getSelection()) {
            return AccessMode.METADATA_ONLY;
        }
        return AccessMode.READ_ONLY;
    }

    private void updateGrantEnablement() {
        AccessMode mode = selectedMode();
        setWarningVisible(writeWarning, mode == AccessMode.READ_WRITE);
        boolean hasSelection = selected != null;
        metadataCheck.setEnabled(hasSelection);
        readCheck.setEnabled(hasSelection && mode != AccessMode.METADATA_ONLY);
        writeCheck.setEnabled(hasSelection && mode == AccessMode.READ_WRITE);
    }

    /**
     * Shows or hides a warning label, relayouting the whole page so the
     * surrounding groups grow or shrink instead of clipping the text.
     */
    private void setWarningVisible(Label label, boolean visible) {
        if (label == null || label.isDisposed()) {
            return;
        }
        label.setVisible(visible);
        if (label.getLayoutData() instanceof GridData gridData) {
            gridData.exclude = !visible;
        }
        Control page = getControl();
        if (page instanceof Composite root && !root.isDisposed()) {
            root.layout(true, true);
            if (root.getParent() != null && !root.getParent().isDisposed()) {
                root.getParent().layout(true, true);
            }
        }
    }

    private void updateAuthState() {
        boolean enabled = authCheck.getSelection();
        tokenText.setEnabled(enabled);
        regenerateButton.setEnabled(enabled);
        copyButton.setEnabled(enabled);
        setWarningVisible(authWarning, !enabled);
    }

    private void updateHostWarning() {
        setWarningVisible(hostWarning, !isLoopbackHost(hostText.getText().trim()));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        try {
            return InetAddress.getByName(host.trim()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private void copyTokenToClipboard() {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(
                new Object[] { tokenText.getText() },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private void updateGrantChecks() {
        if (selected == null) {
            grantsGroup.setText("Access");
            metadataCheck.setSelection(false);
            readCheck.setSelection(false);
            writeCheck.setSelection(false);
        } else {
            grantsGroup.setText("Access for '" + selected.name() + "'");
            metadataCheck.setSelection(metadataIds.contains(selected.id()));
            readCheck.setSelection(readIds.contains(selected.id()));
            writeCheck.setSelection(writeIds.contains(selected.id()));
        }
        updateGrantEnablement();
    }

    private void toggleGrant(Set<String> ids, boolean grant) {
        if (selected == null) {
            return;
        }
        if (grant) {
            ids.add(selected.id());
        } else {
            ids.remove(selected.id());
        }
        connectionsViewer.refresh();
    }

    private String accessSummary(String connectionId) {
        List<String> grants = new ArrayList<>();
        if (metadataIds.contains(connectionId)) {
            grants.add("Metadata");
        }
        if (readIds.contains(connectionId)) {
            grants.add("Read");
        }
        if (writeIds.contains(connectionId)) {
            grants.add("Write");
        }
        return grants.isEmpty() ? "None" : String.join(", ", grants);
    }

    private void loadValues() {
        McpPreferences prefs = new McpPreferences();
        enabledCheck.setSelection(prefs.isServerEnabled());
        autoStartCheck.setSelection(prefs.isAutoStart());
        portText.setText(String.valueOf(prefs.getPort()));
        hostText.setText(prefs.getBindHost());
        timeoutText.setText(String.valueOf(prefs.getQueryTimeoutSec()));
        authCheck.setSelection(prefs.isAuthEnabled());
        String token = prefs.getToken();
        if (token == null || token.isBlank()) {
            token = randomToken();
        }
        tokenText.setText(token);
        updateAuthState();
        updateHostWarning();
        switch (prefs.getAccessMode()) {
            case METADATA_ONLY -> metadataModeRadio.setSelection(true);
            case READ_WRITE -> writeModeRadio.setSelection(true);
            default -> readModeRadio.setSelection(true);
        }
        metadataIds = new LinkedHashSet<>(prefs.getMetadataIds());
        readIds = new LinkedHashSet<>(prefs.getReadIds());
        writeIds = new LinkedHashSet<>(prefs.getWriteIds());
        refreshConnections();
    }

    private void refreshConnections() {
        String keepId = selected == null ? null : selected.id();
        try {
            allConnections = DBeaverBridge.listAllConnections();
            setErrorMessage(null);
        } catch (BridgeException e) {
            McpPlugin.logError("Cannot list DBeaver connections", e);
            setErrorMessage("Cannot list DBeaver connections: " + e.getMessage());
            allConnections = new ArrayList<>();
        }
        connectionsViewer.setInput(allConnections);
        selected = null;
        if (keepId != null) {
            for (ConnectionEntry entry : allConnections) {
                if (keepId.equals(entry.id())) {
                    selected = entry;
                    break;
                }
            }
        }
        updateGrantChecks();
    }

    private void updateStatus() {
        if (statusLabel == null || statusLabel.isDisposed()) {
            return;
        }
        McpServerManager manager = McpServerManager.getInstance();
        String text = manager.isRunning() ? "Status: " + manager.getStatus() : "Status: stopped";
        statusLabel.setText(text);
        statusLabel.getParent().layout();
    }

    private boolean saveValues() {
        int port;
        try {
            port = Integer.parseInt(portText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Port must be a number between 1 and 65535");
            return false;
        }
        if (port < 1 || port > 65535) {
            setErrorMessage("Port must be a number between 1 and 65535");
            return false;
        }
        int timeout;
        try {
            timeout = Integer.parseInt(timeoutText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Query timeout must be a number of seconds (0 disables it)");
            return false;
        }
        if (timeout < 0 || timeout > 86400) {
            setErrorMessage("Query timeout must be between 0 and 86400 seconds");
            return false;
        }
        String bindHost = hostText.getText().trim();
        if (bindHost.isEmpty()) {
            bindHost = McpPreferences.DEFAULT_BIND_HOST;
            hostText.setText(bindHost);
        } else {
            try {
                InetAddress.getByName(bindHost);
            } catch (Exception e) {
                setErrorMessage("Listen host is not a valid host name or IP address");
                return false;
            }
        }
        String token = tokenText.getText().trim();
        if (token.isEmpty()) {
            token = randomToken();
            tokenText.setText(token);
        }
        McpPreferences prefs = new McpPreferences();
        prefs.setServerEnabled(enabledCheck.getSelection());
        prefs.setAutoStart(autoStartCheck.getSelection());
        prefs.setPort(port);
        prefs.setBindHost(bindHost);
        prefs.setQueryTimeoutSec(timeout);
        prefs.setAuthEnabled(authCheck.getSelection());
        prefs.setToken(token);
        prefs.setAccessMode(selectedMode());
        prefs.setMetadataIds(metadataIds);
        prefs.setReadIds(readIds);
        prefs.setWriteIds(writeIds);
        prefs.save();
        setErrorMessage(null);
        return true;
    }

    @Override
    public boolean performOk() {
        if (!saveValues()) {
            return false;
        }
        McpServerManager manager = McpServerManager.getInstance();
        try {
            if (!enabledCheck.getSelection()) {
                manager.stop();
            } else {
                manager.restart();
            }
        } catch (Exception e) {
            McpPlugin.logError("Failed to apply MCP server settings", e);
            setErrorMessage("Settings saved, but the server failed to start: " + e.getMessage());
            return false;
        }
        updateStatus();
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        enabledCheck.setSelection(McpPreferences.DEFAULT_ENABLED);
        autoStartCheck.setSelection(McpPreferences.DEFAULT_AUTO_START);
        portText.setText(String.valueOf(McpPreferences.DEFAULT_PORT));
        timeoutText.setText(String.valueOf(McpPreferences.DEFAULT_QUERY_TIMEOUT_SEC));
        hostText.setText(McpPreferences.DEFAULT_BIND_HOST);
        authCheck.setSelection(McpPreferences.DEFAULT_AUTH_ENABLED);
        updateAuthState();
        updateHostWarning();
        readModeRadio.setSelection(true);
        metadataIds.clear();
        readIds.clear();
        writeIds.clear();
        updateGrantChecks();
        super.performDefaults();
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    }

    private final class ConnectionLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (!(element instanceof ConnectionEntry entry)) {
                return "";
            }
            return switch (columnIndex) {
                case 0 -> entry.name();
                case 1 -> entry.project();
                case 2 -> entry.driver();
                case 3 -> ReadOnlyStrategies.forDriver(entry.driverId()).protection().label();
                case 4 -> accessSummary(entry.id());
                default -> "";
            };
        }

        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }
    }
}
