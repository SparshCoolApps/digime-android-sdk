/*
 * Copyright © 2017 digi.me. All rights reserved.
 */

package me.digi.sdk.core;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


import me.digi.sdk.core.config.ApiConfig;
import me.digi.sdk.core.entities.CAFileResponse;
import me.digi.sdk.core.entities.CAFiles;
import me.digi.sdk.core.internal.AuthorizationException;
import me.digi.sdk.core.internal.Util;
import me.digi.sdk.core.session.CASessionManager;
import me.digi.sdk.core.session.SessionManager;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;


public final class DigiMeClient {
    public static final String TAG = "DigiMeCore";

    private static volatile DigiMeClient singleton;
    private static volatile Executor coreExecutor;
    private static volatile String applicationId;
    private static volatile String applicationName;
    private static volatile String[] contractIds;

    private static volatile boolean debugEnabled = BuildConfig.DEBUG;
    private static Context appContext;
    private static final Object SYNC = new Object();

    //Predefined <meta-data> paths where the sdk looks for necessary items
    public static final String APPLICATION_ID_PATH = "me.digi.sdk.AppId";
    public static final String APPLICATION_NAME_PATH = "me.digi.sdk.AppName";
    public static final String CONSENT_ACCESS_CONTRACTS_PATH = "me.digi.sdk.Contracts";

    private static CASession defaultSession;
    private final List<SDKListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<CASession, DigiMeAPIClient> networkClients;
    private volatile CertificatePinner sslSocketFactory;
    private volatile DigiMeAuthorizationManager authManager;

    SessionManager<CASession> consentAccessSessionManager;

    public final Flow<CAContract> flow;

    private DigiMeClient() {
        this.networkClients = new ConcurrentHashMap<CASession, DigiMeAPIClient>();

        this.flow = new Flow<CAContract>(new FlowLookupInitializer<CAContract>() {
            @Override
            public CAContract create(String identifier) {
                return new CAContract(identifier, DigiMeClient.getApplicationId());
            }
        });
    }

    private static Boolean clientInitialized = false;

    public static synchronized void init(
            final Context appContext) {
        if (clientInitialized) {
            return;
        }

        if (appContext == null) {
            throw new NullPointerException("appContext can not be null.");
        }

        DigiMeClient.appContext = appContext.getApplicationContext();
        DigiMeClient.updatePropertiesFromMetadata(DigiMeClient.appContext);
        if ((applicationId == null) || (applicationId.length() == 0)) {
            throw new DigiMeException("Valid application ID must be set in manifest or by calling setApplicationId.");
        }

        clientInitialized = true;
        getInstance().onStart();
        defaultSession = new CASession("default", 0, "default", null);

        //Check if core app available

        FutureTask<Void> backgroundStartup =
                new FutureTask<>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        getInstance().getSSLSocketFactory();

                        return null;
                    }
                });
        getCoreExecutor().execute(backgroundStartup);
    }

    public static Executor getCoreExecutor() {
        synchronized (SYNC) {
            if (DigiMeClient.coreExecutor == null) {
                DigiMeClient.coreExecutor = AsyncTask.THREAD_POOL_EXECUTOR;
            }
        }
        return DigiMeClient.coreExecutor;
    }


    public static void setCoreExecutor(Executor executor) {
        if (executor == null) {
            throw new NullPointerException("Executor can not be null.");
        }
        synchronized (SYNC) {
            DigiMeClient.coreExecutor = executor;
        }
    }

    public static void checkClientInitialized() {
        if (!DigiMeClient.isClientInitialized()) {
            throw new DigiMeClientException(" DigiMe Core Client has not been properly initialized. You need to call DigiMeClient.init().");
        }
    }

    public static synchronized boolean isClientInitialized() {
        return clientInitialized;
    }

    public static Context getApplicationContext() {
        checkClientInitialized();
        return appContext;
    }

    public static String digiMeSDKVersion() {
        return DigiMeVersion.VERSION;
    }

    public static String getApplicationId() {
        checkClientInitialized();
        return applicationId;
    }

    public static String getApplicationName() {
        checkClientInitialized();
        return applicationName;
    }

    public static void setApplicationName(String applicationName) {
        DigiMeClient.applicationName = applicationName;
    }

    protected void onStart(){
        consentAccessSessionManager = new CASessionManager();
    }

    private synchronized void createSSLSocketFactory() {
        if (sslSocketFactory == null) {
            CertificatePinner pinner = new CertificatePinner.Builder()
                    .add(new ApiConfig().getHost(), "sha256/dJtgu1DIYCnEB2vznevQ8hj9ADPRHzIN4pVG/xqP1DI=")
                    .add(new ApiConfig().getHost(), "sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=")
                    .add(new ApiConfig().getHost(), "sha256/Vjs8r4z+80wjNcr1YKepWQboSIRi63WsWXhIMN+eWys=")
                    .build();
            this.sslSocketFactory = pinner;
        }
    }

    /*
     *  DigiMeClient instance methods
     */

    public static DigiMeClient getInstance() {
        checkClientInitialized();
        if (singleton == null) {
            synchronized (DigiMeClient.class) {
                singleton = new DigiMeClient();
            }
        }
        return singleton;
    }

    public CertificatePinner getSSLSocketFactory() {
        checkClientInitialized();
        if (sslSocketFactory == null) {
            createSSLSocketFactory();
        }
        return sslSocketFactory;
    }

    public SessionManager<CASession> getSessionManager() {
        checkClientInitialized();
        return consentAccessSessionManager;
    }

    public void addListener(final SDKListener listener) {

        synchronized (DigiMeClient.class) {
            if (!listeners.contains(listener))
                listeners.add(listener);
        }
    }

    public boolean removeListener(final SDKListener listener) {

        boolean removed;
        synchronized (DigiMeClient.class) {
            removed = this.listeners.remove(listener);
        }
        return removed;
    }

    public DigiMeAuthorizationManager getAuthManager() {
        if (authManager == null) {
            synchronized (DigiMeClient.class) {
                if (authManager == null) {
                    authManager = new DigiMeAuthorizationManager();
                }
            }
        }
        return authManager;
    }

    /**
     *  Public methods
     */

    public DigiMeAuthorizationManager authorize(Activity activity, SDKCallback<CASession> callback) {
        checkClientInitialized();
        createSession(new AutoSessionForwardCallback(activity, callback));
        return getAuthManager();
    }

    public DigiMeAuthorizationManager authorizeInitializedSession(Activity activity, SDKCallback<CASession> callback) {
        checkClientInitialized();
        DigiMeAuthorizationManager mgr = new DigiMeAuthorizationManager();
        authorizeInitializedSessionWithManager(mgr, activity, callback);
        return mgr;
    }

    public DigiMeAuthorizationManager authorizeInitializedSession(CASession session, Activity activity, SDKCallback<CASession> callback) {
        checkClientInitialized();
        DigiMeAuthorizationManager mgr = new DigiMeAuthorizationManager(DigiMeClient.getApplicationId(), session);
        authorizeInitializedSessionWithManager(mgr, activity, callback);
        return mgr;
    }

    public void authorizeInitializedSessionWithManager(DigiMeAuthorizationManager authManager, Activity activity, SDKCallback<CASession> callback) {
        if (authManager == null) {
            throw new IllegalArgumentException("Authorization Manager can not be null.");
        }
        authManager.beginAuthorization(activity, new AuthorizationForwardCallback(callback));
    }

    public void createSession(SDKCallback<CASession>callback) throws DigiMeException {
        if (!flow.isInitialized()) {
            throw new DigiMeException("No contracts registered! You must have forgotten to add contract Id to the meta-data path \"%s\" or pass the CAContract object to createSession.", CONSENT_ACCESS_CONTRACTS_PATH);
        }
        if (!flow.next()) { flow.rewind().next(); }
        createSession(flow.currentId, callback);
    }

    public void createSession(String contractId, SDKCallback<CASession>callback) throws DigiMeException {
        if (!flow.isInitialized()) {
            throw new DigiMeException("No contracts registered! You must have forgotten to add contract Id to the meta-data path \"%s\" or pass the CAContract object to createSession.", CONSENT_ACCESS_CONTRACTS_PATH);
        }
        CAContract contract = null;
        if (flow.stepTo(contractId)) {
            contract = flow.get();
        } else {
            if (Util.validateContractId(contractId) && DigiMeClient.debugEnabled) {
                throw new DigiMeException("Provided contractId has invalid format.");
            }
            contract = new CAContract(contractId, DigiMeClient.getApplicationId());
        }
        startSessionWithContract(contract, callback);
    }

    public void startSessionWithContract(CAContract contract, SDKCallback<CASession> callback) {
        checkClientInitialized();
        DigiMeAPIClient client = getDefaultApi();
        SessionForwardCallback dispatchCallback;
        if (callback instanceof AutoSessionForwardCallback) {
            dispatchCallback = (AutoSessionForwardCallback) callback;
        } else {
            dispatchCallback = new SessionForwardCallback(callback);
        }
        client.sessionService().getSessionToken(contract).enqueue(dispatchCallback);
    }

    public void getFileList(SDKCallback<CAFiles> callback) {
        checkClientInitialized();
        if (!validateSession(callback)) return;
        getFileListWithSession(getSessionManager().getCurrentSession(), callback);
    }

    public void getFileListWithSession(CASession session, SDKCallback<CAFiles> callback) {
        checkClientInitialized();
        if (!validateSession(session, callback)) return;
        getApi().consentAccessService().list(session.sessionKey).enqueue(new ContentForwardCallback<CAFiles>(callback, null, CAFiles.class));
    }

    public void getFileContent(String fileId, SDKCallback<CAFileResponse> callback) {
        checkClientInitialized();
        if (!validateSession(callback)) return;
        getFileContentWithSession(fileId, getSessionManager().getCurrentSession(), callback);
    }

    public void getFileContentWithSession(String fileId, CASession session, SDKCallback<CAFileResponse> callback) {
        checkClientInitialized();
        if (!validateSession(session, callback)) return;
        if (fileId == null) {
            throw new IllegalArgumentException("File ID can not be null.");
        }
        getApi().consentAccessService().data(session.sessionKey, fileId).enqueue(new ContentForwardCallback<CAFileResponse>(callback, fileId, CAFileResponse.class));
    }

    public DigiMeAPIClient getDefaultApi() {
        return getApi(defaultSession);
    }

    public DigiMeAPIClient getApi() {
        checkClientInitialized();
        final CASession session = consentAccessSessionManager.getCurrentSession();
        if (session == null) {
            return null;
        }
        return getApi(session);
    }

    public DigiMeAPIClient getApi(CASession session) {
        checkClientInitialized();
        if (!networkClients.containsKey(session)) {
            networkClients.putIfAbsent(session, new DigiMeAPIClient(session));
        }
        return networkClients.get(session);
    }

    public DigiMeAPIClient addCustomClient(OkHttpClient client) {
        checkClientInitialized();
        final CASession session = consentAccessSessionManager.getCurrentSession();
        if (session == null) {
            return null;
        }
        return addCustomClient(session, client);
    }

    public DigiMeAPIClient addCustomClient(CASession session, OkHttpClient client) {
        checkClientInitialized();
        DigiMeAPIClient apiClient;
        if (client == null) {
            apiClient = new DigiMeAPIClient(session);
        } else {
            apiClient = new DigiMeAPIClient(client, session);
        }
        return networkClients.put(session, apiClient);
    }

    /**
     *  Private helpers
     */

    private static void updatePropertiesFromMetadata(Context context) {
        if (context == null) {
            return;
        }
        ApplicationInfo ai = null;
        try {
            ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        if (ai == null || ai.metaData == null) {
            return;
        }
        if (applicationId == null) {
            Object appId = ai.metaData.get(APPLICATION_ID_PATH);
            if (appId instanceof String) {
                String appIdString = (String) appId;
                applicationId = Util.digestStringWithLimit(appIdString, 7);

            } else if (appId instanceof Integer) {
                throw new DigiMeException(
                        "App ID must be placed in the strings manifest file");
            }
        }

        if (applicationName == null) {
            applicationName = ai.metaData.getString(APPLICATION_NAME_PATH);
        }

        if (contractIds == null) {
            Object contract = ai.metaData.get(CONSENT_ACCESS_CONTRACTS_PATH);
            if (contract instanceof String) {
                String cont = (String) contract;
                contractIds = new String[]{cont};
            } else if (contract instanceof Integer) {
                String type = context.getResources().getResourceTypeName((int) contract);
                if (type.equalsIgnoreCase("array")) {
                    String[] contracts = context.getResources().getStringArray((int)contract);
                    contractIds = contracts;
                } else if (type.equalsIgnoreCase("string")) {
                    String cnt = context.getResources().getString((int)contract);
                    contractIds = new String[]{cnt};
                } else {
                    throw new DigiMeException(
                            "Allowed types for contract ID are only string-array or string. Check that you have set the correct meta-data type.");
                }
            }
        }
    }

    private boolean validateSession(SDKCallback callback) {
        boolean valid = true;
        if (getSessionManager().getCurrentSession() == null) {
            valid = false;
        } else if (!getSessionManager().getCurrentSession().isValid()) {
            valid = false;
        }
        if (!valid) { callback.failed(new SDKException("Current session is null or invalid")); }
        return valid;
    }

    private boolean validateSession(CASession session, SDKCallback callback) throws IllegalArgumentException {
        boolean valid = true;
        if (session == null) {
            throw new IllegalArgumentException("Session can not be null.");
        } else if (!session.isValid()) {
            valid = false;
        }
        if (!valid) { callback.failed(new SDKException("Current session is invalid")); }
        return valid;
    }

    /**
     *  Iterator for pre-registered CAContract flow
     *
     */

    public abstract class FlowLookupInitializer<T> {

        public abstract T create(String identifier);
    }

    public static final class Flow<T> {
        public static final int START_MARKER = Integer.MAX_VALUE;

        private String currentId;
        private int currentStep;
        private final ArrayList<String> identifiers;
        private final ConcurrentHashMap<String, T> lookup;

        private Flow() {
            this.lookup = new ConcurrentHashMap<>();
            this.identifiers = new ArrayList<>(Arrays.asList(DigiMeClient.contractIds));
            tryInit();
        }

        private Flow(FlowLookupInitializer<T> initializer) {
            this();
            if (this.isInitialized()) {
                for (String id :
                        identifiers) {
                    this.lookup.putIfAbsent(id, initializer.create(id));
                }
            }
        }

        private void tryInit() {
            if (identifiers == null) {
                currentStep = -1;
                currentId = null;
            } else if (identifiers.size() == 0) {
                currentStep = -1;
                currentId = null;
            } else {
                currentStep = START_MARKER;
                currentId = null;
            }
        }

        public int getCurrentStep() {
            return currentStep;
        }

        public String getCurrentId() {
            return currentId;
        }

        public boolean isInitialized() {
            if (currentStep < 0 || (currentStep != START_MARKER && currentId == null)) {
                return false;
            }
            return true;
        }

        public boolean next() {
            if (identifiers == null) { return false; }
            if (currentStep == START_MARKER) { currentStep = -1; }
            if (currentStep + 1 >= identifiers.size()) { return false; }
            currentStep++;
            currentId = identifiers.get(currentStep);

            return true;
        }

        public T get() {
            if (!isInitialized()) { return null; };
            return (T)lookup.get(currentId);
        }

        public boolean stepTo(String identifier) {
            if (identifier == null) { return false; }
            if (identifier.equals(currentId)) { return true; }
            if (lookup.containsKey(identifier)) {
                int index = identifiers.indexOf(identifier);
                if (index >= 0) {
                    currentId = identifier;
                    currentStep = index;
                    return true;
                }
            }
            return false;
        }

        public Flow rewind() {
            tryInit();
            return this;
        }
    }

    /**
     *  Callback wrappers
     */


    class SessionForwardCallback extends SDKCallback<CASession> {
        final SDKCallback<CASession> callback;

        SessionForwardCallback(SDKCallback<CASession> callback) {
            this.callback = callback;
        }

        @Override
        public void succeeded(SDKResponse<CASession> result) {
            final CASession session = result.body;
            if (session == null) {
                callback.failed(new SDKException("Session create returned an empty session!"));
                return;
            }
            CASessionManager sm = (CASessionManager)consentAccessSessionManager;
            sm.setCurrentSession(session);
            getInstance().getApi(session);
            if (callback != null) {
                callback.succeeded(new SDKResponse<>(session, result.response));
            }
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) { iter.next().sessionCreated(session); }
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (callback != null) {
                callback.failed(exception);
            }
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) { iter.next().sessionCreateFailed(exception); }
            }
        }
    }

    class AutoSessionForwardCallback extends SessionForwardCallback {
        private final WeakReference<Activity> callActivity;

        AutoSessionForwardCallback(Activity activity, SDKCallback<CASession> callback) {
            super(callback);
            callActivity = new WeakReference<>(activity);
        }
        @Override
        public void succeeded(SDKResponse<CASession> result) {
            super.succeeded(result);
            if (callActivity != null && callActivity.get() != null) {
                authorizeInitializedSessionWithManager(getAuthManager(), callActivity.get(), callback);
            }
        }
        @Override
        public void failed(SDKException exception) {
            super.failed(exception);
        }
    }

    class AuthorizationForwardCallback extends SDKCallback<CASession> {
        final SDKCallback<CASession> callback;

        AuthorizationForwardCallback(SDKCallback<CASession> callback) {
            this.callback = callback;
        }

        @Override
        public void succeeded(SDKResponse<CASession> result) {
            if (callback != null) {
                callback.succeeded(result);
            }
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) { iter.next().authorizeSucceeded(result.body); }
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (exception instanceof AuthorizationException) {
                determineReason((AuthorizationException) exception);
            } else if (callback != null) {
                callback.failed(exception);
            }
        }

        private void determineReason(AuthorizationException exception) {
            AuthorizationException.Reason reason = exception.getThrowReason();

            if (callback != null && reason != AuthorizationException.Reason.WRONG_CODE) {
                callback.failed(exception);
            }
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) {
                    switch (reason) {
                        case ACCESS_DENIED:
                            iter.next().authorizeDenied(exception);
                            break;
                        case WRONG_CODE:
                        case IN_PROGRESS:
                            iter.next().authorizeFailedWithWrongRequestCode();
                            break;
                    }
                }
            }
        }
    }

    class ContentForwardCallback<T> extends SDKCallback<T> {
        final SDKCallback<T> callback;
        final String reserved;
        private final Class<T> type;

        ContentForwardCallback(SDKCallback<T> callback, Class<T> type) { this(callback, null, type); }
        ContentForwardCallback(SDKCallback<T> callback, String additionalData, Class<T> type) {
            this.callback = callback;
            this.reserved = additionalData;
            this.type = type;
        }

        @Override
        public void succeeded(SDKResponse<T> result) {
            if (callback != null) {
                callback.succeeded(result);
            }
            T returnedObject = result.body;
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) {
                    if (returnedObject instanceof CAFiles) {
                        iter.next().clientRetrievedFileList((CAFiles) returnedObject);
                    } else if (returnedObject instanceof CAFileResponse) {
                        iter.next().contentRetrievedForFile(reserved, (CAFileResponse) returnedObject);
                    }
                }
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (callback != null) {
                callback.failed(exception);
            }
            synchronized (DigiMeClient.class) {
                Iterator<SDKListener> iter = listeners.iterator();
                while (iter.hasNext()) {
                    if (type.equals(CAFiles.class)) {
                        iter.next().clientFailedOnFileList(exception);
                    } else if (type.equals(CAFileResponse.class)) {
                        iter.next().contentRetrieveFailed(reserved, exception);
                    }
                }
            }
        }
    }
}
