package luceedebug;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

public class DapServer implements IDebugProtocolServer {
    private final ICfVm cfvm_;
    private ICfPathTransform pathTransform = new IdentityPathTransform();
    private IDebugProtocolClient clientProxy_;

    private DapServer(ICfVm cfvm) {
        this.cfvm_ = cfvm;

        this.cfvm_.registerStepEventCallback(i64_threadID -> {
            final var i32_threadID = (int)(long)i64_threadID;
            var event = new StoppedEventArguments();
            event.setReason("step");
            event.setThreadId(i32_threadID);
            clientProxy_.stopped(event);
        });

        this.cfvm_.registerBreakpointEventCallback((i64_threadID, i32_bpID) -> {
            System.out.println("(breakpoint callback in dapserver) threadID=" + i64_threadID + ", bpID=" + i32_bpID);
            final int i32_threadID = (int)(long)i64_threadID;
            var event = new StoppedEventArguments();
            event.setReason("breakpoint");
            event.setThreadId(i32_threadID);
            event.setHitBreakpointIds(new Integer[] { i32_bpID });
            clientProxy_.stopped(event);
        });

        this.cfvm_.registerBreakpointsChangedCallback((bpChangedEvent) -> {
            for (var newBreakpoint : bpChangedEvent.newBreakpoints) {
                var bpEvent = new BreakpointEventArguments();
                bpEvent.setBreakpoint(map_cfBreakpoint_to_lsp4jBreakpoint(newBreakpoint));
                bpEvent.setReason("new");
                clientProxy_.breakpoint(bpEvent);
            }

            for (var changedBreakpoint : bpChangedEvent.changedBreakpoints) {
                var bpEvent = new BreakpointEventArguments();
                bpEvent.setBreakpoint(map_cfBreakpoint_to_lsp4jBreakpoint(changedBreakpoint));
                bpEvent.setReason("changed");
                clientProxy_.breakpoint(bpEvent);
            }

            for (var oldBreakpointID : bpChangedEvent.deletedBreakpointIDs) {
                var bpEvent = new BreakpointEventArguments();
                var bp = new Breakpoint();
                bp.setId(oldBreakpointID);
                bpEvent.setBreakpoint(bp);
                bpEvent.setReason("removed");
                clientProxy_.breakpoint(bpEvent);
            }
        });
    }

    static class DapEntry {
        public final DapServer server;
        public final Launcher<IDebugProtocolClient> launcher;
        private DapEntry(DapServer server, Launcher<IDebugProtocolClient> launcher) {
            this.server = server;
            this.launcher = launcher;
        }
    }

    static public DapEntry createForSocket(ICfVm cfvm, String host, int port) {
        var addr = new InetSocketAddress(host, port);
        try (var server = new ServerSocket()) {
            server.bind(addr);
            while (true) {
                var socket = server.accept();
                var dapEntry = create(cfvm, socket.getInputStream(), socket.getOutputStream());
                var future = dapEntry.launcher.startListening();
                future.get(); // block until the connection closes
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    static public DapEntry create(ICfVm cfvm, InputStream in, OutputStream out) {
        var server = new DapServer(cfvm);
        var serverLauncher = DSPLauncher.createServerLauncher(server, in, out);
        server.clientProxy_ = serverLauncher.getRemoteProxy();
        return new DapEntry(server, serverLauncher);
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        var c = new Capabilities();
        c.setSupportsConfigurationDoneRequest(true);
        c.setSupportsSingleThreadExecutionRequests(true);
        return CompletableFuture.completedFuture(c);
    }

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        var maybePathTransform = args.get("pathTransform");
        System.out.println("pathTransform: " + maybePathTransform + " is classof " + maybePathTransform.getClass() + " is a map? " + (maybePathTransform instanceof Map));

        if (maybePathTransform != null && maybePathTransform instanceof Map) {
            var maybeIdePrefix = ((Map<?,?>)maybePathTransform).get("idePrefix");
            var maybeCfPrefix = ((Map<?,?>)maybePathTransform).get("cfPrefix");
            if (maybeCfPrefix != null && maybeIdePrefix != null && maybeCfPrefix instanceof String && maybeIdePrefix instanceof String) {
                pathTransform = new PrefixPathTransform((String)maybeIdePrefix, (String)maybeCfPrefix);
            }
        }

        clientProxy_.initialized();

        return CompletableFuture.completedFuture(null);
    }

    static final Comparator<org.eclipse.lsp4j.debug.Thread> threadNameComparator = Comparator.comparing(thread -> thread.getName().toLowerCase());

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        var lspThreads = new ArrayList<org.eclipse.lsp4j.debug.Thread>();

        for (var threadRef : cfvm_.getThreadListing()) {
            var lspThread = new org.eclipse.lsp4j.debug.Thread();
            lspThread.setId((int)threadRef.uniqueID());
            lspThread.setName(threadRef.name());
            lspThreads.add(lspThread);
        }
        
        lspThreads.sort(threadNameComparator);

        var response = new ThreadsResponse();
        response.setThreads(lspThreads.toArray(new org.eclipse.lsp4j.debug.Thread[lspThreads.size()]));

        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        var lspFrames = new ArrayList<org.eclipse.lsp4j.debug.StackFrame>();

        for (var cfFrame : cfvm_.getStackTrace(args.getThreadId())) {
            final var source = new Source();
            source.setPath(pathTransform.cfToIde(cfFrame.getSourceFilePath()));
    
            final var lspFrame = new org.eclipse.lsp4j.debug.StackFrame();
            lspFrame.setId((int)cfFrame.getId());
            lspFrame.setName(cfFrame.getName());
            lspFrame.setLine(cfFrame.getLine());
            lspFrame.setSource(source);

            lspFrames.add(lspFrame);
        }

        var response = new StackTraceResponse();
        response.setStackFrames(lspFrames.toArray(new org.eclipse.lsp4j.debug.StackFrame[lspFrames.size()]));
        response.setTotalFrames(lspFrames.size());

        return CompletableFuture.completedFuture(response);
    }

    @Override
	public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        var scopes = new ArrayList<Scope>();
        for (var entity : cfvm_.getScopes(args.getFrameId())) {
            var scope = new Scope();
            scope.setName(entity.getName());
            scope.setVariablesReference((int)entity.getVariablesReference());
            scope.setIndexedVariables(entity.getIndexedVariables());
            scope.setNamedVariables(entity.getNamedVariables());
            scope.setExpensive(entity.getExpensive());
            scopes.add(scope);
        }
        var result = new ScopesResponse();
        result.setScopes(scopes.toArray(size -> new Scope[size]));
        return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        var variables = new ArrayList<Variable>();
        for (var entity : cfvm_.getVariables(args.getVariablesReference())) {
            var variable = new Variable();
            variable.setName(entity.getName());
            variable.setVariablesReference((int)entity.getVariablesReference());
            variable.setIndexedVariables(entity.getIndexedVariables());
            variable.setNamedVariables(entity.getNamedVariables());
            variable.setValue(entity.getValue());
            variables.add(variable);
        }
        var result = new VariablesResponse();
        result.setVariables(variables.toArray(size -> new Variable[size]));
        return CompletableFuture.completedFuture(result);
	}

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        final var absPath = pathTransform.ideToCf(args.getSource().getPath());
        System.out.println("bp for " + args.getSource().getPath() + " -> " + absPath);
        final int size = args.getBreakpoints().length;
        final int[] lines = new int[size];
        for (int i = 0; i < size; ++i) {
            lines[i] = args.getBreakpoints()[i].getLine();
        }

        var result = new ArrayList<Breakpoint>();
        for (var cfBreakpoint : cfvm_.bindBreakpoints(absPath, lines)) {
            result.add(map_cfBreakpoint_to_lsp4jBreakpoint(cfBreakpoint));
        }
        
        var response = new SetBreakpointsResponse();
        response.setBreakpoints(result.toArray(len -> new Breakpoint[len]));

        return CompletableFuture.completedFuture(response);
    }

    private Breakpoint map_cfBreakpoint_to_lsp4jBreakpoint(IBreakpoint cfBreakpoint) {
        var bp = new Breakpoint();
        bp.setLine(cfBreakpoint.getLine());
        bp.setId(cfBreakpoint.getID());
        bp.setVerified(cfBreakpoint.getIsBound());
        return bp;
    }

    /**
     * We don't really support this, but not sure how to say that; there doesn't seem to be a "supports exception breakpoints"
     * flag in the init response? vscode always sends this?
     * 
     * in cppdap, it didn't (against the same client code), so there is likely some
     * initialization configuration that can set whether the client sends this or not
     * 
     * Seems adding support for "configuration done" means clients don't need to send this request
     * https://microsoft.github.io/debug-adapter-protocol/specification#Events_Initialized
     * 
     * @unsupported
     */
    @Override
	public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
        // set success false?
		return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
	}

    /**
     * Can we disable the UI for this in the client plugin?
     * 
     * @unsupported
     */
	public CompletableFuture<Void> pause(PauseArguments args) {
        // set success false?
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        cfvm_.clearAllBreakpoints();
        cfvm_.continueAll();
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
		cfvm_.continue_(args.getThreadId());
        return CompletableFuture.completedFuture(new ContinueResponse());
	}

    @Override
	public CompletableFuture<Void> next(NextArguments args) {
		cfvm_.stepOver(args.getThreadId());
        return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> stepIn(StepInArguments args) {
        cfvm_.stepIn(args.getThreadId());
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> stepOut(StepOutArguments args) {
        cfvm_.stepOut(args.getThreadId());
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
		return CompletableFuture.completedFuture(null);
	}
}
