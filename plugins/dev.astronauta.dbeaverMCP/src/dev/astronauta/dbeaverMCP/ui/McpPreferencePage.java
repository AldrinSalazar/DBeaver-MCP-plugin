package dev.astronauta.dbeaverMCP.ui;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;


import dev.astronauta.dbeaverMCP.McpPlugin;
import dev.astronauta.dbeaverMCP.McpPreferences;
import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import dev.astronauta.dbeaverMCP.ServerSettings;
import dev.astronauta.dbeaverMCP.db.AccessPolicy;
import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.db.DBeaverBridge;
import dev.astronauta.dbeaverMCP.db.ConnectionCatalog.ConnectionEntry;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import dev.astronauta.dbeaverMCP.server.McpServerManager;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
import org.eclipse.ui.statushandlers.StatusManager;

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
    private ConnectionGrants grants;
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
        regenerateButton.addListener(SWT.Selection, e -> tokenText.setText(ServerSettings.newToken()));
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
            if (saveValues()) {
                runServerAction("Starting MCP server", () -> McpServerManager.getInstance().restartNow());
            }
        });
        Button stopButton = new Button(buttons, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.addListener(SWT.Selection, e -> {
            McpServerManager.getInstance().cancelAutoStart();
            runServerAction("Stopping MCP server", () -> McpServerManager.getInstance().stop());
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
            grants.enableAll(allConnections, selectedMode());
            updateGrantChecks();
            connectionsViewer.refresh();
        });
        Button disableAll = new Button(side, SWT.PUSH);
        disableAll.setText("Disable all");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(disableAll);
        disableAll.addListener(SWT.Selection, e -> {
            grants.clear();
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
        metadataCheck.addListener(SWT.Selection, e -> editGrants(() -> grants.setMetadata(selected.id(), metadataCheck.getSelection())));
        readCheck.addListener(SWT.Selection, e -> editGrants(() -> grants.setRead(selected.id(), readCheck.getSelection())));
        writeCheck.addListener(SWT.Selection, e -> editGrants(() -> grants.setWrite(selected.id(), writeCheck.getSelection())));
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
        writeCheck.setEnabled(hasSelection && mode == AccessMode.READ_WRITE && !selected.readOnly());
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
        // No DNS lookups on the SWT thread. Treat other names conservatively.
        return Set.of("localhost", "localhost.", "127.0.0.1", "::1", "[::1]")
            .contains(host.trim().toLowerCase(java.util.Locale.ROOT));
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
            AccessPolicy policy = grants.snapshot(selectedMode());
            metadataCheck.setSelection(policy.canExpose(selected.id()));
            readCheck.setSelection(policy.readIds().contains(selected.id()));
            writeCheck.setSelection(policy.writeIds().contains(selected.id()));
        }
        updateGrantEnablement();
    }

    private void editGrants(Runnable edit) {
        if (selected == null) {
            return;
        }
        edit.run();
        updateGrantChecks();
        connectionsViewer.refresh();
    }

    private String accessSummary(String connectionId) {
        AccessPolicy policy = grants.snapshot(selectedMode());
        List<String> labels = new ArrayList<>();
        if (policy.canExpose(connectionId)) {
            labels.add("Metadata");
        }
        if (policy.readIds().contains(connectionId)) {
            labels.add("Read");
        }
        if (policy.writeIds().contains(connectionId)) {
            labels.add("Write");
        }
        return labels.isEmpty() ? "None" : String.join(", ", labels);
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
            token = ServerSettings.newToken();
        }
        tokenText.setText(token);
        updateAuthState();
        updateHostWarning();
        selectMode(prefs.getAccessMode());
        grants = new ConnectionGrants(prefs.accessPolicy());
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
        String text = "Status: " + manager.getStatus();
        statusLabel.setText(text);
        statusLabel.getParent().layout();
    }

    private void selectMode(AccessMode mode) {
        metadataModeRadio.setSelection(mode == AccessMode.METADATA_ONLY);
        readModeRadio.setSelection(mode == AccessMode.READ_ONLY);
        writeModeRadio.setSelection(mode == AccessMode.READ_WRITE);
    }

    private boolean saveValues() {
        ServerSettings settings;
        try {
            settings = ServerSettings.parse(enabledCheck.getSelection(), autoStartCheck.getSelection(),
                hostText.getText(), portText.getText(), authCheck.getSelection(), tokenText.getText(),
                timeoutText.getText());
        } catch (IllegalArgumentException e) {
            setErrorMessage(e.getMessage());
            return false;
        }
        hostText.setText(settings.host());
        tokenText.setText(settings.token());
        McpPreferences prefs = new McpPreferences();
        settings.saveTo(prefs);
        prefs.setAccessMode(selectedMode());
        AccessPolicy policy = grants.snapshot(selectedMode());
        prefs.setMetadataIds(policy.metadataIds());
        prefs.setReadIds(policy.readIds());
        prefs.setWriteIds(policy.writeIds());
        prefs.save();
        setErrorMessage(null);
        return true;
    }

    @FunctionalInterface
    private interface ServerAction {
        void run() throws Exception;
    }

    private void runServerAction(String name, ServerAction action) {
        statusLabel.setText(name + "...");
        var display = statusLabel.getDisplay();
        Job job = Job.create(name, monitor -> {
            String failure = null;
            try {
                action.run();
            } catch (Exception e) {
                McpPlugin.logError(name + " failed", e);
                failure = e.getMessage() == null || e.getMessage().isBlank()
                    ? name + " failed: " + e.getClass().getSimpleName() : e.getMessage();
            }
            String error = failure;
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!statusLabel.isDisposed()) {
                        setErrorMessage(error);
                        updateStatus();
                    } else if (error != null) {
                        StatusManager.getManager().handle(
                            new Status(IStatus.ERROR, McpPlugin.PLUGIN_ID, error), StatusManager.SHOW);
                    }
                });
            }
            return error == null ? Status.OK_STATUS : new Status(IStatus.ERROR, McpPlugin.PLUGIN_ID, error);
        });
        job.setRule(McpServerManager.LIFECYCLE_RULE);
        job.setSystem(true);
        job.schedule();
    }

    @Override
    public boolean performOk() {
        if (!saveValues()) {
            return false;
        }
        McpServerManager.getInstance().cancelAutoStart();
        runServerAction("Applying MCP server settings", () -> McpServerManager.getInstance().restart());
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
        selectMode(McpPreferences.DEFAULT_ACCESS_MODE);
        grants.clear();
        updateGrantChecks();
        connectionsViewer.refresh();
        super.performDefaults();
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
