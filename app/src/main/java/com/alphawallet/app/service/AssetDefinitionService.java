package com.alphawallet.app.service;

import static com.alphawallet.app.repository.TokenRepository.getWeb3jService;
import static com.alphawallet.app.repository.TokensRealmSource.IMAGES_DB;
import static com.alphawallet.ethereum.EthereumNetworkBase.ARBITRUM_MAIN_ID;
import static com.alphawallet.ethereum.EthereumNetworkBase.MAINNET_ID;
import static com.alphawallet.ethereum.EthereumNetworkBase.SEPOLIA_TESTNET_ID;
import static com.alphawallet.token.tools.TokenDefinition.TOKENSCRIPT_CURRENT_SCHEMA;
import static com.alphawallet.token.tools.TokenDefinition.TOKENSCRIPT_REPO_SERVER;
import static com.alphawallet.token.tools.TokenDefinition.UNCHANGED_SCRIPT;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;

import com.alphawallet.app.BuildConfig;
import com.alphawallet.app.entity.ContractLocator;
import com.alphawallet.app.entity.ContractType;
import com.alphawallet.app.entity.EasAttestation;
import com.alphawallet.app.entity.FragmentMessenger;
import com.alphawallet.app.entity.QueryResponse;
import com.alphawallet.app.entity.TokenLocator;
import com.alphawallet.app.entity.attestation.SchemaRecord;
import com.alphawallet.app.entity.nftassets.NFTAsset;
import com.alphawallet.app.entity.tokens.Attestation;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.entity.tokens.TokenInfo;
import com.alphawallet.app.entity.tokenscript.EventUtils;
import com.alphawallet.app.entity.tokenscript.TokenScriptFile;
import com.alphawallet.app.entity.tokenscript.TokenscriptFunction;
import com.alphawallet.app.repository.TokenLocalSource;
import com.alphawallet.app.repository.TokensRealmSource;
import com.alphawallet.app.repository.entity.RealmAuxData;
import com.alphawallet.app.repository.entity.RealmCertificateData;
import com.alphawallet.app.repository.entity.RealmTokenScriptData;
import com.alphawallet.app.ui.HomeActivity;
import com.alphawallet.app.util.Utils;
import com.alphawallet.app.viewmodel.HomeViewModel;
import com.alphawallet.app.web3j.StructuredDataEncoder;
import com.alphawallet.ethereum.EthereumNetworkBase;
import com.alphawallet.ethereum.NetworkInfo;
import com.alphawallet.token.entity.ActionModifier;
import com.alphawallet.token.entity.AttestationDefinition;
import com.alphawallet.token.entity.Attribute;
import com.alphawallet.token.entity.AttributeInterface;
import com.alphawallet.token.entity.ContractAddress;
import com.alphawallet.token.entity.ContractInfo;
import com.alphawallet.token.entity.EvaluateSelection;
import com.alphawallet.token.entity.EventDefinition;
import com.alphawallet.token.entity.FunctionDefinition;
import com.alphawallet.token.entity.MethodArg;
import com.alphawallet.token.entity.ParseResult;
import com.alphawallet.token.entity.SigReturnType;
import com.alphawallet.token.entity.TSAction;
import com.alphawallet.token.entity.TSSelection;
import com.alphawallet.token.entity.TokenScriptResult;
import com.alphawallet.token.entity.TokenscriptContext;
import com.alphawallet.token.entity.TokenscriptElement;
import com.alphawallet.token.entity.TransactionResult;
import com.alphawallet.token.entity.ViewType;
import com.alphawallet.token.entity.XMLDsigDescriptor;
import com.alphawallet.token.tools.Numeric;
import com.alphawallet.token.tools.TokenDefinition;

import org.jetbrains.annotations.NotNull;
import org.web3j.abi.DefaultFunctionEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.xml.sax.SAXException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Case;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;
import io.realm.exceptions.RealmException;
import io.realm.exceptions.RealmPrimaryKeyConstraintException;
import timber.log.Timber;


/**
 * AssetDefinitionService is the only place where we interface with XML files.
 * This is to avoid duplicating resources
 * and also provide a consistent way to get XML values
 */

public class AssetDefinitionService implements ParseResult, AttributeInterface
{
    public static final String ASSET_SUMMARY_VIEW_NAME = "item-view";
    public static final String ASSET_DETAIL_VIEW_NAME = "view";
    private static final String ASSET_DEFINITION_DB = "ASSET-db.realm";
    private static final String BUNDLED_SCRIPT = "bundled";
    private static final long CHECK_TX_LOGS_INTERVAL = 20;
    private static final String EIP5169_ISSUER = "EIP5169-IPFS";
    private static final String EIP5169_CERTIFIER = "Smart Token Labs";
    private static final String EIP5169_KEY_OWNER = "Contract Owner"; //TODO Source this from the contract via owner()
    private static final String TS_EXTENSION = ".tsml";

    private final Context context;
    private final IPFSServiceType ipfsService;

    private final Map<String, Long> assetChecked;                //Mapping of contract address to when they were last fetched from server
    private FileObserver fileObserver;                     //Observer which scans the override directory waiting for file change
    private FileObserver fileObserverQ;                    //Observer for Android Q directory
    private final NotificationService notificationService;
    private final RealmManager realmManager;
    private final TokensService tokensService;
    private final TokenLocalSource tokenLocalSource;
    private final AlphaWalletService alphaWalletService;
    private TokenDefinition cachedDefinition = null;
    private final ConcurrentHashMap<String, EventDefinition> eventList = new ConcurrentHashMap<>(); //List of events built during file load
    private final Semaphore assetLoadingLock;  // used to block if someone calls getAssetDefinitionASync() while loading
    private Disposable eventListener;           // timer thread that periodically checks event logs for scripts that require events
    private final Semaphore eventConnection;
    private FragmentMessenger homeMessenger;

    private final TokenscriptFunction tokenscriptUtility;

    @Nullable
    private Disposable checkEventDisposable;

    /* Designed with the assumption that only a single instance of this class at any given time
     *  ^^ The "service" part of AssetDefinitionService is the keyword here.
     *  This is shorthand in the project to indicate this is a singleton that other classes inject.
     *  This is the design pattern of the app. See class RepositoriesModule for constructors which are called at App init only */
    public AssetDefinitionService  (IPFSServiceType ipfsSvs, Context ctx, NotificationService svs,
                                    RealmManager rm, TokensService tokensService,
                                    TokenLocalSource trs, AlphaWalletService alphaService)
    {
        context = ctx;
        ipfsService = ipfsSvs;
        assetChecked = new ConcurrentHashMap<>();
        notificationService = svs;
        realmManager = rm;
        alphaWalletService = alphaService;
        this.tokensService = tokensService;
        tokenscriptUtility = new TokenscriptFunction()
        {
        }; //no overridden functions
        tokenLocalSource = trs;
        assetLoadingLock = new Semaphore(1);
        eventConnection = new Semaphore(1);
        //deleteAllEventData();
        loadAssetScripts();
    }

    public TokenLocalSource getTokenLocalSource()
    {
        return tokenLocalSource;
    }

    /**
     * Load all TokenScripts
     * <p>
     * This order has to be observed because it's an expected developer override order. If a script is placed in the /AlphaWallet directory
     * it is expected to override the one fetched from the repo server.
     * If a developer clicks on a script intent this script is expected to override the one fetched from the server.
     */
    private void loadAssetScripts()
    {
        try
        {
            assetLoadingLock.acquire(); // acquire the semaphore here to prevent attributes from being fetched until loading is complete
            // See flow above for details
        }
        catch (InterruptedException e)
        {
            Timber.e(e);
        }

        List<String> handledHashes = checkRealmScriptsForChanges();
        loadNewFiles(handledHashes);

        //executes after observable completes due to blockingForEach
        loadInternalAssets();
        startDirectoryListeners();
        finishLoading();
    }

    private List<String> checkRealmScriptsForChanges()
    {
        //1. Signed files downloaded from server.
        //2. Files placed in the Android OS external directory (Android/data/<App Package Name>/files)
        //3. Files placed in the /AlphaWallet directory.
        //Depending on the order placed, files can be overridden. A file downloaded from the server is
        //overridden by a script for the same token placed in the /AlphaWallet directory.

        //First check all the previously parsed scripts to check for any changes
        List<String> handledHashes = new ArrayList<>();
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmResults<RealmTokenScriptData> realmData = realm.where(RealmTokenScriptData.class)
                    .findAll();

            for (RealmTokenScriptData entry : realmData)
            {
                if (handledHashes.contains(entry.getFileHash()))
                    continue; //already checked - note that if a contract has multiple origins it could have more than one entry
                //get file
                TokenScriptFile tsf = new TokenScriptFile(context, entry.getFilePath());
                handledHashes.add(entry.getFileHash());
                if (!tsf.exists() || tsf.fileChanged(entry.getFileHash()))
                {
                    deleteTokenScriptFromRealm(realm, entry.getFileHash());

                    if (tsf.exists())
                    {
                        handledHashes.add(tsf.calcMD5()); //add the hash of the new file
                        //re-parse script, file hash has changed
                        final TokenDefinition td = parseFile(tsf.getInputStream());
                        cacheSignature(tsf)
                                .map(definition -> getOriginContracts(td))
                                .subscribeOn(Schedulers.io())
                                .observeOn(Schedulers.io())
                                .subscribe(success -> fileLoadComplete(success, tsf, td),
                                        error -> handleFileLoadError(error, tsf))
                                .isDisposed();
                    }
                }
                else if (entry.hasEvents())
                {
                    //populate events
                    TokenDefinition td = parseFile(tsf.getInputStream());
                    addToEventList(td);
                }
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return handledHashes;
    }

    private void loadNewFiles(List<String> handledHashes)
    {
        //Now parse each file found to check for
        Observable.fromIterable(buildFileList())
                .filter(File::isFile)
                .filter(this::allowableExtension)
                .filter(File::canRead)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .blockingForEach(file -> {  //load sequentially
                    try
                    {
                        final TokenScriptFile tsf = new TokenScriptFile(context, file.getAbsolutePath());
                        final String hash = tsf.calcMD5();
                        if (handledHashes.contains(hash)) return; //already handled this?
                        final TokenDefinition td = parseFile(tsf.getInputStream());
                        cacheSignature(file)
                                .map(definition -> getOriginContracts(td))
                                .subscribeOn(Schedulers.io())
                                .observeOn(Schedulers.io())
                                .subscribe(success -> fileLoadComplete(success, tsf, td),
                                        error -> handleFileLoadError(error, file))
                                .isDisposed();
                    }
                    catch (Exception e)
                    {
                        handledHashes.add(new TokenScriptFile(context, file.getAbsolutePath()).calcMD5());
                        handleFileLoadError(e, file);
                    }
                });
    }

    private void deleteTokenScriptFromRealm(Realm realm, String fileHash) throws RealmException
    {
        //delete from realm
        realm.executeTransactionAsync(r -> {
            //have to remove all instances of this hash
            RealmResults<RealmTokenScriptData> hashInstances = r.where(RealmTokenScriptData.class)
                    .equalTo("fileHash", fileHash)
                    .findAll();

            RealmCertificateData realmCert = r.where(RealmCertificateData.class)
                    .equalTo("instanceKey", fileHash)
                    .findFirst();

            if (realmCert != null) realmCert.deleteFromRealm();

            //now delete all associated event data; script event descriptions may have changed
            for (RealmTokenScriptData script : hashInstances)
            {
                deleteEventDataForScript(script);
            }

            hashInstances.deleteAllFromRealm();
        });
    }

    private void deleteEventDataForScript(RealmTokenScriptData scriptData)
    {
        try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
        {
            realm.executeTransaction(r -> {
                RealmResults<RealmAuxData> realmEvents = r.where(RealmAuxData.class)
                        .equalTo("tokenAddress", scriptData.getOriginTokenAddress())
                        .or()
                        .contains("instanceKey", scriptData.getOriginTokenAddress())
                        .findAll();
                realmEvents.deleteAllFromRealm();
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    //This loads bundled TokenScripts in the /assets directory eg xDAI bridge
    private void loadInternalAssets()
    {
        deleteAllInternalScriptFromRealm();
        //First load the bundled scripts
        Observable.fromIterable(getLocalTSMLFiles())
                .subscribeOn(Schedulers.io())
                .subscribe(this::addContractAssets, this::onError)
                .isDisposed();
    }

    private void deleteAllInternalScriptFromRealm()
    {
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransactionAsync(r -> {
                //have to remove all instances of this hash
                RealmResults<RealmTokenScriptData> hashInstances = r.where(RealmTokenScriptData.class)
                        .equalTo("fileHash", BUNDLED_SCRIPT)
                        .findAll();
                hashInstances.deleteAllFromRealm();
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    private void handleFileLoadError(Throwable throwable, File file)
    {
        Timber.d("ERROR WHILE PARSING: " + file.getName() + " : " + throwable.getMessage());
    }

    private TokenDefinition fileLoadComplete(List<ContractLocator> originContracts, TokenScriptFile file, TokenDefinition td)
    {
        if (originContracts.size() == 0) return td; //no action needed

        boolean hasEvents = td.hasEvents();

        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            //check for out-of-date script in the secure (downloaded) zone
            if (isInSecureZone(file) && !td.nameSpace.equals(TokenDefinition.TOKENSCRIPT_NAMESPACE))
            {
                //delete this file and check downloads for update
                removeFile(file.getAbsolutePath());
                loadScriptFromServer(getFileName(file));
                return td;
            }

            final String hash = file.calcMD5();

            realm.executeTransaction(r -> {
                for (ContractLocator cl : originContracts)
                {
                    //String entryKey = getTSDataKey(cl.chainId, cl.address);
                    String entryKey = getTSDataKeyTemp(cl.chainId, cl.address);
                    RealmTokenScriptData entry = r.where(RealmTokenScriptData.class)
                            .equalTo("instanceKey", entryKey)
                            .findFirst();

                    if (entry == null)
                    {
                        entry = r.createObject(RealmTokenScriptData.class, entryKey);
                    }

                    //don't allow secure zone script to replace debug script; require user to delete the debug script first
                    //TODO: Warn user if secure zone script is updated and if they have a debug script
                    if (TextUtils.isEmpty(entry.getFilePath()) || isInSecureZone(entry.getFilePath()))
                    {
                        entry.setFileHash(hash);
                        entry.setFilePath(file.getAbsolutePath());
                        entry.setNames(td.getTokenNameList());
                        entry.setViewList(td.getViews());
                        entry.setHasEvents(hasEvents);
                    }
                }
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return td;
    }

    private String getTSDataKeyTemp(long chainId, String address)
    {
        if (address.equalsIgnoreCase(tokensService.getCurrentAddress()))
        {
            address = "ethereum";
        }

        return address.toLowerCase() + "-" + chainId;
    }

    //Start listening to the two script directories for files dropped in.
    //Why two directories? User may not want to allow AlphaWallet to have read file permission,
    //but we still want to allow them to be able to click on scripts in eg Telegram and install them
    //without needing to go through a permission screen
    //Using AlphaWallet directory is more convenient for developers using eg Android Studio to drop files into their phone
    private void startDirectoryListeners()
    {
        //listen for new files dropped into app external directory
        fileObserverQ = startFileListener(context.getExternalFilesDir("").getAbsolutePath());
        startAlphaWalletListener();
    }

    public void startAlphaWalletListener()
    {
        //listen for new files dropped into AlphaWallet directory, if we have permission
        if (checkReadPermission())
        {
            File alphaWalletDir = new File(
                    Environment.getExternalStorageDirectory()
                            + File.separator + HomeViewModel.ALPHAWALLET_DIR);

            if (alphaWalletDir.exists())
            {
                fileObserver = startFileListener(alphaWalletDir.getAbsolutePath());
            }
        }
    }

    public void onDestroy()
    {
        if (fileObserver != null) fileObserver.stopWatching();
        if (fileObserverQ != null) fileObserverQ.stopWatching();
        if (eventListener != null && !eventListener.isDisposed()) eventListener.dispose();
    }

    // Note that parse order has to change due to the improved parse method: we write the first found file to database and skip the others
    // So - include the debug override file first
    private List<File> buildFileList()
    {
        List<File> fileList = new ArrayList<>();
        try
        {
            File[] files;
            //first include the files in the AlphaWallet directory - these have the highest priority
            if (checkReadPermission())
            {
                File alphaWalletDir = new File(
                        Environment.getExternalStorageDirectory()
                                + File.separator + HomeViewModel.ALPHAWALLET_DIR);

                if (alphaWalletDir.exists())
                {
                    files = alphaWalletDir.listFiles();
                    if (files != null) fileList.addAll(Arrays.asList(files));
                }
            }

            //then include the files in the app external directory - these are placed here when there's no file permission
            files = context.getExternalFilesDir("").listFiles();
            if (files != null)
                fileList.addAll(Arrays.asList(files)); //now add files in the app's external directory; /Android/data/[app-name]/files. These override internal

            //finally the files downloaded from the server
            files = context.getFilesDir().listFiles();
            if (files != null)
                fileList.addAll(Arrays.asList(files)); //first add files in app internal area - these are downloaded from the server
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        if (fileList.size() == 0) finishLoading();

        return fileList;
    }

    @Override
    public boolean resolveOptimisedAttr(ContractAddress contract, Attribute attr, TransactionResult transactionResult)
    {
        boolean optimised = false;
        if (attr.function == null) return false;
        Token checkToken = tokensService.getToken(contract.chainId, contract.address);
        if (checkToken == null) return false;
        boolean hasNoParams = attr.function.parameters == null || attr.function.parameters.size() == 0;

        switch (attr.function.method)
        {
            case "balanceOf":
                //ensure the arg check for this function call is checking the correct balance address
                for (MethodArg arg : attr.function.parameters)
                {
                    if (arg.parameterType.equals("address") && arg.element.ref.equals("ownerAddress"))
                    {
                        transactionResult.result = checkToken.balance.toString();
                        transactionResult.resultTime = checkToken.updateBlancaTime;
                        optimised = true;
                        break;
                    }
                }
                break;
            case "name":
                if (hasNoParams)
                {
                    transactionResult.result = checkToken.tokenInfo.name;
                    transactionResult.resultTime = checkToken.updateBlancaTime;
                    optimised = true;
                }
                break;
            case "symbol":
                if (hasNoParams)
                {
                    transactionResult.result = checkToken.tokenInfo.symbol;
                    transactionResult.resultTime = checkToken.updateBlancaTime;
                    optimised = true;
                }
                break;
            case "decimals":
                if (hasNoParams)
                {
                    transactionResult.result = String.valueOf(checkToken.tokenInfo.decimals);
                    transactionResult.resultTime = checkToken.updateBlancaTime;
                    optimised = true;
                }
                break;
        }

        return optimised;
    }

    @Override
    public String getWalletAddr()
    {
        return tokensService.getCurrentAddress();
    }

    @Override
    public long getLastTokenUpdate(long chainId, String address)
    {
        long txUpdateTime = 0;
        Token token = tokensService.getToken(chainId, address);
        if (token != null)
        {
            txUpdateTime = token.lastTxTime;
        }

        return txUpdateTime;
    }

    @Override
    public Attribute fetchAttribute(ContractInfo origin, String attributeName)
    {
        String addr = null;
        TokenDefinition td = null;
        long chainId = origin.addresses.keySet().iterator().next();
        if (origin.addresses.get(chainId).size() > 0) addr = origin.addresses.get(chainId).get(0);
        if (addr != null) td = getAssetDefinition(chainId, addr);
        if (td != null)
        {
            return td.attributes.get(attributeName);
        }
        else
        {
            return null;
        }
    }

    @Override
    public TokenScriptResult.Attribute fetchAttrResult(ContractAddress origin, Attribute attr, BigInteger tokenId)
    {
        TokenDefinition td = getAssetDefinition(origin.chainId, origin.address);
        Token originToken = tokensService.getToken(origin.chainId, origin.address);
        if (originToken == null || td == null) return null;

        //produce result
        return tokenscriptUtility.fetchAttrResult(originToken, attr, tokenId, td, this, ViewType.VIEW).blockingGet();
    }

    /**
     * Refreshes the stored values for a list of attributes
     * @param token
     * @return
     */
    public Single<Boolean> refreshAttributes(Token token, TokenDefinition td, BigInteger tokenId, List<Attribute> attrs)
    {
        return Single.fromCallable(() -> {
            //run through attributes first
            for (Attribute attr : attrs)
            {
                //check if any of these attributes take TokenID
                if (attr.usesTokenId())
                {
                    updateAttributeResult(token, td, attr, tokenId);
                }
                else
                {
                    updateAttributeResult(token, td, attr, BigInteger.ZERO);
                }
            }
            return true;
        });
    }

    public Single<Boolean> resetAttributes(TokenDefinition td)
    {
        return Single.fromCallable(() -> {
            try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
            {
                for (ContractInfo cInfo : td.contracts.values())
                {
                    for (Map.Entry<Long, List<String>> e : cInfo.addresses.entrySet())
                    {
                        for (String addr : e.getValue())
                        {
                            String dataBaseKey = addr + "-*-" + e.getKey() + "-*-" + "-func-key";
                            RealmResults<RealmAuxData> functionResults = realm.where(RealmAuxData.class)
                                    .like("instanceKey", dataBaseKey, Case.INSENSITIVE)
                                    .findAll();

                            if (functionResults.isEmpty()) continue;

                            realm.executeTransaction(r -> {
                                functionResults.deleteAllFromRealm();
                            });
                        }
                    }

                }
            }
            catch (Exception e)
            {
                Timber.e(e);
            }

            return true;
        });
    }

    /**
     * Refreshes the stored values for all Attribute results for all tokenIds
     * @param token
     * @return
     */
    public Single<Boolean> refreshAllAttributes(Token token)
    {
        TokenDefinition td = getAssetDefinition(token);
        if (td == null) return Single.fromCallable(() -> false);

        return Single.fromCallable(() -> {
            //run through attributes first
            for (Map.Entry<String, Attribute> attrEntry : td.attributes.entrySet())
            {
                //check if any of these attributes take TokenID
                if (attrEntry.getValue().usesTokenId())
                {
                    //run through each tokenId and update
                    for (BigInteger tokenId : token.getTokenAssets().keySet())
                    {
                        updateAttributeResult(token, td, attrEntry.getValue(), tokenId);
                    }
                }
                else
                {
                    updateAttributeResult(token, td, attrEntry.getValue(), BigInteger.ZERO);
                }
            }
            return true;
        });
    }

    private void updateAttributeResult(Token token, TokenDefinition td, Attribute attr, BigInteger tokenId)
    {
        ContractAddress useAddress = new ContractAddress(attr.function); //always use the function attribute's address
        tokenscriptUtility.fetchResultFromEthereum(token, useAddress, attr, tokenId, td, this) // Fetch function result from blockchain
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(txResult -> storeAuxData(getWalletAddr(), txResult))
                .isDisposed();
    }

    public void addLocalRefs(Map<String, String> refs)
    {
        tokenscriptUtility.addLocalRefs(refs);
    }

    private Attribute getTypeFromList(String key, List<Attribute> attrList)
    {
        for (Attribute attr : attrList)
        {
            if (attr.name.equals(key)) return attr;
        }

        return null;
    }

    /**
     * Fetch attributes from local storage; not using contract lookup
     *
     * @param token
     * @param tokenId
     * @return
     */
    public TokenScriptResult getTokenScriptResult(Token token, BigInteger tokenId)
    {
        TokenScriptResult result = new TokenScriptResult();
        TokenDefinition definition = getAssetDefinition(token);
        if (definition != null)
        {
            for (String key : definition.attributes.keySet())
            {
                result.setAttribute(key, getTokenscriptAttr(definition, tokenId, key));
            }
        }

        return result;
    }

    private TokenScriptResult.Attribute getTokenscriptAttr(TokenDefinition td, BigInteger tokenId, String attribute)
    {
        TokenScriptResult.Attribute result = null;
        Attribute attrtype = td.attributes.get(attribute);
        try
        {
            if (attrtype == null)
            {
                return null;
            }
            else if (attrtype.event != null)
            {
                result = new TokenScriptResult.Attribute(attrtype, tokenId, "unsupported encoding");
            }
            else if (attrtype.function != null)
            {
                //should be sourced from function
                ContractAddress cAddr = new ContractAddress(attrtype.function);
                TransactionResult tResult = getFunctionResult(cAddr, attrtype, tokenId); //t.getTokenIdResults(BigInteger.ZERO);
                result = tokenscriptUtility.parseFunctionResult(tResult, attrtype);//  attrtype.function.parseFunctionResult(tResult, attrtype);
            }
            else
            {
                BigInteger val = tokenId.and(attrtype.bitmask).shiftRight(attrtype.bitshift);
                result = new TokenScriptResult.Attribute(attrtype, attrtype.processValue(val), attrtype.getSyntaxVal(attrtype.toString(val)));
            }
        }
        catch (Exception e)
        {
            result = new TokenScriptResult.Attribute(attrtype, tokenId, "unsupported encoding");
        }

        return result;
    }

    public TokenScriptResult.Attribute getAttribute(Token token, BigInteger tokenId, String attribute)
    {
        TokenDefinition definition = getAssetDefinition(token);
        if (definition != null && definition.attributes.containsKey(attribute))
        {
            return getTokenscriptAttr(definition, tokenId, attribute);
        }
        else
        {
            return null;
        }
    }

    private boolean checkReadPermission()
    {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private TokenDefinition getDefinition(String tsKey)
    {
        TokenDefinition result = null;
        String[] elements = tsKey.split("-");
        if (elements.length < 2)
        {
            return null;
        }

        String address = elements[0];
        long chainId = Long.parseLong(elements[1]);
        //try cache
        if (cachedDefinition != null)
        {
            //only match holding token
            ContractInfo holdingContracts = cachedDefinition.contracts.get(cachedDefinition.holdingToken);
            if (holdingContracts != null && holdingContracts.addresses.containsKey(chainId))
            {
                for (String addr : holdingContracts.addresses.get(chainId))
                {
                    if (addr.equalsIgnoreCase(address.toLowerCase())) return cachedDefinition;
                }
            }
        }

        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", tsKey)
                    .findFirst();

            if (tsData != null)
            {
                if (tsData.getFileHash().equals(BUNDLED_SCRIPT)) //handle bundled scripts
                {
                    cachedDefinition = getBundledDefinition(tsData.getFilePath());
                }
                else
                {
                    TokenScriptFile tf = new TokenScriptFile(context, tsData.getFilePath());
                    cachedDefinition = parseFile(tf.getInputStream());
                }
                result = cachedDefinition;
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return result;
    }

    public TokenScriptFile getTokenScriptFile(long chainId, String address)
    {
        //pull from database
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", getTSDataKeyTemp(chainId, address))// getTSDataKey(chainId, address))
                    .findFirst();

            if (tsData != null && tsData.getFilePath() != null)
            {
                return new TokenScriptFile(context, tsData.getFilePath());
            }
        }

        return new TokenScriptFile(context);
    }

    public String getDebugPath(String fileName)
    {
        return context.getExternalFilesDir("") + File.separator + fileName;
    }

    public TokenScriptFile getTokenScriptFile(Token token)
    {
        String fileName = token.getTSKey() + TS_EXTENSION;
        //first try debug file
        TokenScriptFile tsf = new TokenScriptFile(context, getDebugPath(fileName));
        if (tsf.exists())
        {
            return tsf;
        }

        File f = new File(context.getFilesDir(), fileName);
        if (f.exists())
        {
            return new TokenScriptFile(context, f.getAbsolutePath());
        }

        return new TokenScriptFile(context);
    }

    /**
     * Get asset definition given contract address
     *
     * @param address
     * @return
     */
    public TokenDefinition getAssetDefinition(long chainId, String address)
    {
        if (address == null) return null;

        if (address.equalsIgnoreCase(tokensService.getCurrentAddress()))
        {
            address = "ethereum";
        }
        //is asset definition currently read?
        final TokenDefinition assetDef = getDefinition(getTSDataKeyTemp(chainId, address));
        if (assetDef == null && !address.equals("ethereum"))
        {
            //try web
            loadScriptFromServer(address.toLowerCase()); //this will complete asynchronously and display will be updated
        }

        return assetDef; // if nothing found use default
    }

    public TokenDefinition getAssetDefinition(Token token)
    {
        if (token == null)
        {
            return null;
        }

        try
        {
            TokenScriptFile tsf = getTokenScriptFile(token);
            return parseFile(tsf.getInputStream());
        }
        catch (Exception e)
        {
            // NOP
        }

        return null;
    }

    public Single<TokenDefinition> getAssetDefinitionASync(long chainId, String address)
    {
        if (address == null)
        {
            return Single.fromCallable(TokenDefinition::new);
        }

        String convertedAddr = (address.equalsIgnoreCase(tokensService.getCurrentAddress())) ? "ethereum" : address.toLowerCase();
        return getAssetDefinitionASync(getDefinition(getTSDataKeyTemp(chainId, address)), convertedAddr);
    }

    private Single<TokenDefinition> getAssetDefinitionASync(final TokenDefinition assetDef, final String contractName)
    {
        if (assetDef != null)
        {
            return Single.fromCallable(() -> assetDef);
        }
        else if (!contractName.equals("ethereum"))
        {
            //at this stage, this script isn't replacing any existing script, so it's safe to write to database without checking if we need to delete anything
            return fetchXMLFromServer(contractName.toLowerCase())
                    .flatMap(this::handleNewTSFile);
        }
        else return Single.fromCallable(TokenDefinition::new);
    }

    public Single<TokenDefinition> getAssetDefinitionASync(Token token)
    {
        String contractName = token.tokenInfo.address;
        if (contractName.equalsIgnoreCase(tokensService.getCurrentAddress()))
        {
            contractName = "ethereum";
        }

        // hold until asset definitions have finished loading
        waitForAssets();

        return getAssetDefinitionASync(getDefinition(token.getTSKey()), contractName);
    }

    private void waitForAssets()
    {
        try
        {
            assetLoadingLock.acquire();
        }
        catch (InterruptedException e)
        {
            Timber.e(e);
        }
        finally
        {
            assetLoadingLock.release();
        }
    }

    public String getTokenName(long chainId, String address, int count)
    {
        String tokenName = null;
        if (address.equalsIgnoreCase(tokensService.getCurrentAddress())) address = "ethereum";
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", getTSDataKeyTemp(chainId, address))
                    .findFirst();

            if (tsData != null)
            {
                tokenName = tsData.getName(count);
            }
        }

        return tokenName;
    }

    public Token getTokenFromService(long chainId, String address)
    {
        return tokensService.getToken(chainId, address);
    }

    /**
     * Get the issuer label given the contract address
     * Note: this is optimised so as we don't need to keep loading in definitions as the user scrolls
     *
     * @param token
     * @return
     */
    public String getIssuerName(Token token)
    {
        String issuer = token.getNetworkName();

        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", token.getTSKey())
                    .findFirst();

            if (tsData != null)
            {
                XMLDsigDescriptor sig = getCertificateFromRealm(tsData.getFileHash());
                if (sig != null && sig.keyName != null) issuer = sig.keyName;
            }
        }
        catch (Exception e)
        {
            // no action
        }

        return issuer;
    }

    private void loadScriptFromServer(String correctedAddress)
    {
        //first check the last time we tried this session
        if (assetChecked.get(correctedAddress) == null || (System.currentTimeMillis() > (assetChecked.get(correctedAddress) + 1000L * 60L * 60L)))
        {
            fetchXMLFromServer(correctedAddress)
                    .flatMap(this::cacheSignature)
                    .flatMap(this::handleNewTSFile)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::loadComplete, this::onError).isDisposed();
        }
    }

    private void loadComplete(TokenDefinition td)
    {
        Timber.d("TS LOAD: %s", td.getTokenName(1));
    }

    private void onError(Throwable throwable)
    {
        Timber.e(throwable);
    }

    private TokenDefinition parseFile(InputStream xmlInputStream) throws Exception
    {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        {
            locale = context.getResources().getConfiguration().getLocales().get(0);
        }
        else
        {
            locale = context.getResources().getConfiguration().locale;
        }

        return new TokenDefinition(
                xmlInputStream, locale, this);
    }

    private Single<TokenDefinition> handleNewTSFile(File newFile)
    {
        if (!newFile.exists())
        {
            return signalUnchangedScript(newFile.getName());
        }
        //1. check validity & check for origin tokens
        //2. check for existing and check if this is a debug file or script from server
        //3. update signature data
        //4. update database
        TokenScriptFile tsf = new TokenScriptFile(context, newFile.getAbsolutePath());
        try
        {
            boolean isDebugOverride = tsf.isDebug();
            final TokenDefinition td = parseFile(tsf.getInputStream());
            List<ContractLocator> originContracts = getOriginContracts(td);
            //remove all old definitions & certificates
            updateScriptEntriesInRealm(originContracts, isDebugOverride, tsf.calcMD5());
            cachedDefinition = null;
            return cacheSignature(tsf)
                    .map(contracts -> fileLoadComplete(originContracts, tsf, td));
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return Single.fromCallable(TokenDefinition::new);
    }

    private Single<TokenDefinition> signalUnchangedScript(String name)
    {
        TokenDefinition placeHolder = new TokenDefinition();
        if (name.equals(UNCHANGED_SCRIPT))
        {
            placeHolder.nameSpace = UNCHANGED_SCRIPT;
        }
        return Single.fromCallable(() -> placeHolder);
    }

    private void updateScriptEntriesInRealm(List<ContractLocator> origins, boolean isDebug, String newFileHash)
    {
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransaction(r -> {
                for (ContractLocator cl : origins)
                {
                    String entryKey = getTSDataKeyTemp(cl.chainId, cl.address);
                    RealmTokenScriptData realmData = r.where(RealmTokenScriptData.class)
                            .equalTo("instanceKey", entryKey)
                            .findFirst();

                    if (realmData != null && (isDebug || isInSecureZone(realmData.getFilePath()))) //delete the existing entry if this script is debug, or if the old script is in the server area
                    {
                        RealmCertificateData realmCert = r.where(RealmCertificateData.class)
                                .equalTo("instanceKey", realmData.getFileHash())
                                .findFirst();
                        if (realmCert != null && !realmData.getFileHash().equals(newFileHash))
                        {
                            realmCert.deleteFromRealm(); //don't delete cert if new cert will overwrite it
                        }
                        deleteEventDataForScript(realmData);
                        if (!realmData.getFileHash().equals(newFileHash))
                        {
                            realmData.deleteFromRealm();
                        }
                    }
                }
            });
        }
    }

    //Call contract and check for script
    private Single<File> fetchTokenScriptFromContract(Token token, MutableLiveData<Boolean> updateFlag)
    {
        return token.getScriptURI()
                .map(uri -> {
                    if (!TextUtils.isEmpty(uri) && updateFlag != null)
                    {
                        updateFlag.postValue(true);
                    }

                    return uri;
                })
                .map(uri -> compareExistingScript(token, uri))
                .map(uri -> new Pair<>(uri, downloadScript(uri, 0)))
                .map(scriptData -> storeEntry(token, scriptData));
    }

    private File storeEntry(Token token, Pair<String, Pair<String, Boolean>> scriptData) throws IOException
    {
        if (TextUtils.isEmpty(scriptData.second.first) || scriptData.second.first.equals(UNCHANGED_SCRIPT))
        {
            return new File(UNCHANGED_SCRIPT); // blank file with UNCHANGED name
        }

        String tokenKey = token.getTSKey();

        File storeFile = storeFile(tokenKey, scriptData.second);

        TokenScriptFile tsf = new TokenScriptFile(context, storeFile.getAbsolutePath());

        String fileHash = tsf.calcMD5();

        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransaction(r -> {
                //String entryKey = getTSDataKey(token.tokenInfo.chainId, token.tokenInfo.address);
                RealmTokenScriptData entry = r.where(RealmTokenScriptData.class)
                        .equalTo("instanceKey", tokenKey)
                        .findFirst();

                if (entry == null)
                {
                    entry = r.createObject(RealmTokenScriptData.class, tokenKey);
                }

                entry.setFileHash(fileHash);
                if (scriptData.second.second) entry.setIpfsPath(scriptData.first); //if sourced from IPFS store path
                entry.setFilePath(storeFile.getAbsolutePath());
            });
        }
        catch (Exception e)
        {
            Timber.w(e);
        }

        return storeFile;
    }

    private String compareExistingScript(Token token, String uri)
    {
        //TODO: calculate and use the IPFS CID to validate existing script against IPFS locator
        String returnUri = uri;
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            String entryKey = token.getTSKey(); //getTSDataKey(token.tokenInfo.chainId, token.tokenInfo.address);

            TokenScriptFile tsf = getTokenScriptFile(token);

            RealmTokenScriptData entry = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", entryKey)
                    .findFirst();

            if (entry != null && !TextUtils.isEmpty(entry.getFileHash())
                    && !TextUtils.isEmpty(entry.getFilePath())
                    && !TextUtils.isEmpty(entry.getIpfsPath())
                    && entry.getIpfsPath().equals(uri)
                    && tsf.exists())
            {
                returnUri = UNCHANGED_SCRIPT;
            }
        }
        catch (Exception e)
        {
            Timber.w(e);
        }

        return returnUri;
    }

    private Single<File> tryServerIfRequired(File contractScript, String address)
    {
        if (contractScript.exists() || contractScript.getName().equals(UNCHANGED_SCRIPT))
        {
            return Single.fromCallable(() -> contractScript);
        }
        else
        {
            return fetchXMLFromServer(address);
        }
    }

    private Single<File> fetchXMLFromServer(String address)
    {
        return Single.fromCallable(() -> {
            final File defaultReturn = new File("");
            if (address.equals("")) return defaultReturn;

            File result = getDownloadedXMLFile(address);

            //peek to see if this file exists
            long fileTime = 0;
            if (result != null && result.exists())
            {
                TokenDefinition td = getTokenDefinition(result);
                if (definitionIsOutOfDate(td))
                {
                    removeFile(result.getAbsolutePath());
                    assetChecked.put(address, 0L);
                }
                else
                {
                    fileTime = result.lastModified();
                }
            }
            else
            {
                result = defaultReturn;
            }

            if (assetChecked.get(address) != null && (System.currentTimeMillis() > (assetChecked.get(address) + 1000L * 60L * 60L)))
                return result;

            String sb = TOKENSCRIPT_REPO_SERVER +
                    TOKENSCRIPT_CURRENT_SCHEMA +
                    "/" +
                    address;

            Pair<String, Boolean> downloadResponse = downloadScript(sb, fileTime);
            if (!TextUtils.isEmpty(downloadResponse.first))
            {
                result = storeFile(address, downloadResponse);
            }

            assetChecked.put(address, System.currentTimeMillis());

            return result;
        });
    }

    private Pair<String, Boolean> downloadScript(String Uri, long currentFileTime)
    {
        if (Uri.equals(UNCHANGED_SCRIPT))
        {
            return new Pair<>(UNCHANGED_SCRIPT, false);
        }

        boolean isIPFS = Utils.isIPFS(Uri);

        try
        {
            QueryResponse response = ipfsService.performIO(Uri, getHeaders(currentFileTime));
            switch (response.code)
            {
                default:
                case HttpURLConnection.HTTP_NOT_MODIFIED:
                    break;
                case HttpURLConnection.HTTP_OK:
                    return new Pair<>(response.body, isIPFS);
            }
        }
        catch (Exception e)
        {
            if (!TextUtils.isEmpty(Uri)) //throws on empty, which is expected
            {
                Timber.w(e);
            }
        }

        return new Pair<>("", false);
    }

    private String[] getHeaders(long currentFileTime) throws PackageManager.NameNotFoundException
    {
        SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String dateFormat = format.format(new Date(currentFileTime));

        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageInfo(
                context.getPackageName(), 0);
        String appVersion = info.versionName;
        String OSVersion = String.valueOf(Build.VERSION.RELEASE);

        return new String[] {
                "Accept", "text/xml; charset=UTF-8",
                "X-Client-Name", "AlphaWallet",
                "X-Client-Version", appVersion,
                "X-Platform-Name", "Android",
                "X-Platform-Version", OSVersion,
                "If-Modified-Since", dateFormat
        };
    }

    private boolean definitionIsOutOfDate(TokenDefinition td)
    {
        return td != null && !td.nameSpace.equals(TokenDefinition.TOKENSCRIPT_NAMESPACE);
    }

    private void finishLoading()
    {
        assetLoadingLock.release();
        if (Utils.isAddressValid(tokensService.getCurrentAddress()))
        {
            updateEventBlockTimes();
            startEventListener();
        }
    }

    private void removeFile(String filename)
    {
        try
        {
            File fileToDelete = new File(filename);
            fileToDelete.delete();
        }
        catch (Exception e)
        {
            //ignore error
        }
    }

    /**
     * Only used for loading bundled TokenScripts
     *
     * @param asset
     * @return
     */
    private boolean addContractAssets(String asset)
    {
        try (InputStream input = context.getResources().getAssets().open(asset))
        {
            TokenDefinition token = parseFile(input);
            TokenScriptFile tsf = new TokenScriptFile(context, asset);
            ContractInfo holdingContracts = token.contracts.get(token.holdingToken);
            if (holdingContracts != null)
            {
                //some Android versions don't have stream()
                for (long network : holdingContracts.addresses.keySet())
                {
                    for (String address : holdingContracts.addresses.get(network))
                    {
                        updateRealmForBundledScript(network, address, asset, token);
                    }
                    XMLDsigDescriptor AWSig = new XMLDsigDescriptor();
                    String hash = tsf.calcMD5();
                    AWSig.result = "pass";
                    AWSig.issuer = "AlphaWallet";
                    AWSig.keyName = "AlphaWallet";
                    AWSig.type = SigReturnType.SIGNATURE_PASS;
                    tsf.determineSignatureType(AWSig);
                    storeCertificateData(hash, AWSig);
                }
                return true;
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
        return false;
    }

    private TokenDefinition getBundledDefinition(String asset)
    {
        TokenDefinition td = null;
        try (InputStream input = context.getResources().getAssets().open(asset))
        {
            td = parseFile(input);
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return td;
    }

    private void updateRealmForBundledScript(long chainId, String address, String asset, TokenDefinition td)
    {
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransaction(r -> {
                String entryKey = getTSDataKeyTemp(chainId, address);
                RealmTokenScriptData entry = r.where(RealmTokenScriptData.class)
                        .equalTo("instanceKey", entryKey)
                        .findFirst();

                if (entry == null) entry = r.createObject(RealmTokenScriptData.class, entryKey);
                entry.setFilePath(asset);
                entry.setViewList(td.getViews());
                entry.setNames(td.getTokenNameList());
                entry.setHasEvents(td.hasEvents());
                entry.setViewList(td.getViews());
                entry.setFileHash(BUNDLED_SCRIPT);
            });
        }
    }

    public TokenDefinition getTokenDefinition(File file)
    {
        try (FileInputStream input = new FileInputStream(file))
        {
            return parseFile(input);
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return null;
    }

    private List<ContractLocator> getOriginContracts(TokenDefinition tokenDef)
    {
        ContractInfo holdingContracts = tokenDef.contracts.get(tokenDef.holdingToken);

        if (holdingContracts != null)
        {
            addToEventList(tokenDef);
            return ContractLocator.fromContractInfo(holdingContracts);
        }

        return new ArrayList<>();
    }

    private void addToEventList(TokenDefinition tokenDef)
    {
        for (String attrName : tokenDef.attributes.keySet())
        {
            Attribute attr = tokenDef.attributes.get(attrName);
            if (attr.event != null && attr.event.contract != null)
            {
                checkAddToEventList(attr.event); //note: event definition contains link back to the contract it refers to
            }
        }

        if (tokenDef.getActivityCards().size() > 0)
        {
            for (String activityName : tokenDef.getActivityCards().keySet())
            {
                EventDefinition ev = tokenDef.getActivityEvent(activityName);
                checkAddToEventList(ev);
            }
        }
    }

    private void checkAddToEventList(EventDefinition ev)
    {
        String eventKey = ev.getEventKey();
        eventList.put(eventKey, ev);
    }

    public void stopEventListener()
    {
        if (eventListener != null && !eventListener.isDisposed()) eventListener.dispose();
        if (checkEventDisposable != null && !checkEventDisposable.isDisposed())
            checkEventDisposable.dispose();
    }

    public void startEventListener()
    {
        if (assetLoadingLock.availablePermits() == 0) return;

        if (eventListener != null && !eventListener.isDisposed()) eventListener.dispose();
        eventListener = Observable.interval(0, CHECK_TX_LOGS_INTERVAL, TimeUnit.SECONDS)
                .doOnNext(l -> {
                    checkEvents()
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(() -> {}, t -> {}) //results are handled within logging function
                            .isDisposed();
                }).subscribe();
    }

    private Completable checkEvents()
    {
        //check events for corresponding tokens
        return Completable.fromAction(() -> {
            for (EventDefinition ev : eventList.values())
            {
                getEvent(ev);
            }
        });
    }

    private void getEvent(EventDefinition ev)
    {
        try
        {
            EthFilter filter = getEventFilter(ev);
            if (filter == null) return;
            if (BuildConfig.DEBUG)
                eventConnection.acquire(); //prevent overlapping event calls while debugging
            final String walletAddress = tokensService.getCurrentAddress();
            Web3j web3j = getWeb3jService(ev.getEventChainId());

            checkEventDisposable = handleLogs(ev, filter, web3j, walletAddress)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.computation())
                    .subscribe();
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    private Single<String> handleLogs(EventDefinition ev, EthFilter filter, Web3j web3j, final String walletAddress)
    {
        return Single.fromCallable(() -> {
            String txHash = "";
            try
            {
                EthLog ethLogs = web3j.ethGetLogs(filter).send();
                txHash = processLogs(ev, ethLogs.getLogs(), walletAddress);
            }
            catch (Exception e)
            {
                Timber.e(e);
            }
            finally
            {
                if (BuildConfig.DEBUG) eventConnection.release();
            }
            return txHash;
        });
        //More elegant, but requires a private node
//        return web3j.ethLogFlowable(filter)
//                .subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(log -> {
//            Timber.d("log.toString(): " +  log.toString());
//            //TODO here: callback to event service listener
//        }, this::onLogError);
    }

    private EthFilter getEventFilter(EventDefinition ev) throws Exception
    {
        long chainId = ev.getEventChainId();
        String address = ev.getEventContractAddress();

        Token originToken = tokensService.getToken(chainId, address);

        if (originToken == null) return null; //TODO: Handle non origin token events

        return EventUtils.generateLogFilter(ev, originToken, this);
    }

    private String processLogs(EventDefinition ev, List<EthLog.LogResult> logs, String walletAddress)
    {
        if (logs == null || logs.size() == 0) return ""; //early return
        long chainId = ev.contract.addresses.keySet().iterator().next();
        Web3j web3j = getWeb3jService(chainId);

        String firstTxHash = "";

        int index = logs.size() - 1;

        for (int i = index; i >= 0; i--)
        {
            EthLog.LogResult<?> ethLog = logs.get(i);
            String txHash = ((Log) ethLog.get()).getTransactionHash();
            if (TextUtils.isEmpty(firstTxHash)) firstTxHash = txHash;
            String selectVal = EventUtils.getSelectVal(ev, ethLog);
            BigInteger blockNumber = ((Log) ethLog.get()).getBlockNumber();

            if (blockNumber.compareTo(ev.readBlock) > 0)
            {
                //Should store the latest event value
                storeLatestEventBlockTime(walletAddress, ev, blockNumber);
            }

            if (ev.parentAttribute != null)
            {
                storeEventValue(walletAddress, ev, ethLog, ev.parentAttribute, selectVal);
            }
            else
            {
                EthBlock txBlock = EventUtils.getBlockDetails(((Log) ethLog.get()).getBlockHash(), web3j).blockingGet();
                long blockTime = txBlock.getBlock().getTimestamp().longValue();

                storeActivityValue(walletAddress, ev, ethLog, blockTime, ev.activityName);

                TransactionsService.addTransactionHashFetch(txHash, chainId, walletAddress);
            }
        }

        return firstTxHash;
    }

    private void storeLatestEventBlockTime(String walletAddress, EventDefinition ev, BigInteger readBlock)
    {
        ev.readBlock = readBlock.add(BigInteger.ONE);
        try (Realm realm = realmManager.getRealmInstance(walletAddress))
        {
            long chainId = ev.getEventChainId();
            String eventAddress = ev.getEventContractAddress();
            String eventName = ev.activityName != null ? ev.activityName : ev.attributeName;
            String databaseKey = TokensRealmSource.eventBlockKey(chainId, eventAddress, ev.type.name, ev.filter);
            realm.executeTransactionAsync(r -> {
                RealmAuxData realmToken = r.where(RealmAuxData.class)
                        .equalTo("instanceKey", databaseKey)
                        .findFirst();
                if (realmToken == null)
                    realmToken = r.createObject(RealmAuxData.class, databaseKey);
                realmToken.setResultTime(System.currentTimeMillis());
                realmToken.setResult(ev.readBlock.toString(16));
                realmToken.setFunctionId(eventName);
                realmToken.setChainId(chainId);
                realmToken.setTokenAddress("");
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    private void storeActivityValue(String walletAddress, EventDefinition ev, EthLog.LogResult log, long blockTime, String activityName)
    {
        BigInteger tokenId = EventUtils.getTokenId(ev, log);
        //split out all the event data
        String valueList = EventUtils.getAllTopics(ev, log);

        String selectVal = EventUtils.getSelectVal(ev, log);

        ContractAddress eventContractAddress = new ContractAddress(ev.getEventChainId(),
                ev.getEventContractAddress());

        //store this data
        String txHash = ((Log) log.get()).getTransactionHash();
        String key = TokensRealmSource.eventActivityKey(txHash, ev.type.name);
        storeAuxData(walletAddress, key, tokenId, valueList, activityName, eventContractAddress, blockTime); //store the event itself
    }

    private void storeAuxData(String walletAddress, String databaseKey, BigInteger tokenId, String eventData, String activityName, ContractAddress cAddr, long blockTime)
    {
        try (Realm realm = realmManager.getRealmInstance(walletAddress))
        {
            realm.executeTransactionAsync(r -> {
                RealmAuxData realmToken = r.where(RealmAuxData.class)
                        .equalTo("instanceKey", databaseKey)
                        .findFirst();
                if (realmToken == null)
                    realmToken = r.createObject(RealmAuxData.class, databaseKey);
                realmToken.setResultTime(blockTime);
                realmToken.setResult(eventData);
                realmToken.setFunctionId(activityName);
                realmToken.setChainId(cAddr.chainId);
                realmToken.setTokenId(tokenId.toString(16));
                realmToken.setTokenAddress(cAddr.address);
                realmToken.setResultReceivedTime(System.currentTimeMillis());
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    private void storeEventValue   (String walletAddress, EventDefinition ev, EthLog.LogResult log, Attribute attr,
                                    String selectVal)
    {
        //store result
        BigInteger tokenId = EventUtils.getTokenId(ev, log);

        ContractAddress eventContractAddress = new ContractAddress(ev.getEventChainId(),
                ev.getEventContractAddress());
        TransactionResult txResult = getFunctionResult(eventContractAddress, attr, tokenId);
        txResult.result = attr.getSyntaxVal(selectVal);

        long blockNumber = ((Log) log.get()).getBlockNumber().longValue();

        //Update the entry for the attribute if required
        if (txResult.resultTime == 0 || blockNumber >= txResult.resultTime)
        {
            txResult.resultTime = blockNumber;
            storeAuxData(walletAddress, txResult);
        }
    }

    private boolean allowableExtension(File file)
    {
        int index = file.getName().lastIndexOf(".");
        if (index >= 0)
        {
            String extension = file.getName().substring(index + 1);
            switch (extension)
            {
                case "xml":
                case "tsml":
                    return true;
                default:
                    break;
            }
        }

        return false;
    }

    private String getFileName(File file)
    {
        String name = file.getName();
        int index = name.lastIndexOf(".");
        if (index > 0)
        {
            return name.substring(0, index);
        }
        else
        {
            return null;
        }
    }

    private boolean isAddress(File file)
    {
        String name = getFileName(file);
        if (name != null) return Utils.isAddressValid(name);
        else return false;
    }

    /**
     * This is used to retrieve the file from the secure area in order to check the date.
     * Note: it only finds files previously downloaded from the server
     *
     * @param contractAddress
     * @return
     */
    private File getDownloadedXMLFile(String contractAddress)
    {
        //if in secure area will simply be address + XML
        String filename = contractAddress + TS_EXTENSION;
        File file = new File(context.getFilesDir(), filename);
        if (file.exists() && file.canRead())
        {
            return file;
        }

        File[] files = context.getFilesDir().listFiles();
        for (File f : files)
        {
            if (f.getName().equalsIgnoreCase(filename)) return f;
        }

        return null;
    }

    private List<String> getScriptsInSecureZone()
    {
        List<String> checkScripts = new ArrayList<>();
        File[] files = context.getFilesDir().listFiles();
        Observable.fromArray(files)
                .filter(File::isFile)
                .filter(this::allowableExtension)
                .filter(File::canRead)
                .filter(this::isAddress)
                .forEach(file -> checkScripts.add(getFileName(file))).isDisposed();

        return checkScripts;
    }

    private boolean isInSecureZone(File file)
    {
        return file.getPath().contains(context.getFilesDir().getPath());
    }

    private boolean isInSecureZone(String file)
    {
        return file.contains(context.getFilesDir().getPath());
    }

    /* Add cached signature if uncached files found. */
    private Single<File> cacheSignature(File file)
    {
        if (file.getName().equals(UNCHANGED_SCRIPT))
        {
            return Single.fromCallable(() -> file);
        }

        return Single.fromCallable(() -> {
            if (file.canRead())
            {
                TokenScriptFile tsf = new TokenScriptFile(context, file.getAbsolutePath());
                String hash = tsf.calcMD5();

                //pull data from realm
                XMLDsigDescriptor sig = getCertificateFromRealm(hash);
                if (sig == null || sig.keyName == null)
                {
                    //fetch signature and store in realm
                    sig = alphaWalletService.checkTokenScriptSignature(tsf);
                    tsf.determineSignatureType(sig);
                    storeCertificateData(hash, sig);
                }
            }

            return file;
        });
    }

    private void storeCertificateData(String hash, XMLDsigDescriptor sig) throws RealmException
    {
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransaction(r -> {
                //if signature present, then just update
                boolean isNew = false;
                RealmCertificateData realmData = r.where(RealmCertificateData.class)
                        .equalTo("instanceKey", hash)
                        .findFirst();

                if (realmData == null)
                {
                    realmData = r.createObject(RealmCertificateData.class, hash);
                    isNew = true;
                }
                realmData.setFromSig(sig);
                if (isNew)
                {
                    r.insertOrUpdate(realmData);
                }
            });
        }
    }

    private XMLDsigDescriptor getCertificateFromRealm(String hash)
    {
        XMLDsigDescriptor sig = null;
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmCertificateData realmCert = realm.where(RealmCertificateData.class)
                    .equalTo("instanceKey", hash)
                    .findFirst();

            if (realmCert != null)
            {
                sig = new XMLDsigDescriptor();
                sig.issuer = realmCert.getIssuer();
                sig.certificateName = realmCert.getCertificateName();
                sig.keyName = realmCert.getKeyName();
                sig.keyType = realmCert.getKeyType();
                sig.result = realmCert.getResult();
                sig.subject = realmCert.getSubject();
                sig.type = realmCert.getType();
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return sig;
    }

    private XMLDsigDescriptor IPFSSigDescriptor()
    {
        XMLDsigDescriptor sig = new XMLDsigDescriptor();
        sig.issuer = EIP5169_ISSUER;
        sig.certificateName = EIP5169_CERTIFIER;
        sig.keyName = EIP5169_KEY_OWNER;
        sig.keyType = "ECDSA";
        sig.result = "Pass";
        sig.subject = "";
        sig.type = SigReturnType.SIGNATURE_PASS;
        return sig;
    }

    /**
     * Use internal directory to store contracts fetched from the server
     *
     * @param address
     * @param result
     * @return
     * @throws
     */
    private File storeFile(String address, Pair<String, Boolean> result) throws IOException
    {
        if (result.first == null || result.first.length() < 10) return new File("");

        String fName = address + TS_EXTENSION;

        //Store received files in the internal storage area - no need to ask for permissions
        File file = new File(context.getFilesDir(), fName);

        FileOutputStream fos = new FileOutputStream(file);
        OutputStream os = new BufferedOutputStream(fos);
        os.write(result.first.getBytes());
        fos.flush();
        os.close();
        fos.close();

        //handle signature for IPFS
        if (result.second)
        {
            TokenScriptFile tsf = new TokenScriptFile(context, file.getAbsolutePath());
            String hash = tsf.calcMD5();
            storeCertificateData(hash, IPFSSigDescriptor());
        }

        return file;
    }

    public boolean hasDefinition(Token token)
    {
        boolean hasDefinition = false;
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", token.getTSKey())
                    .findFirst();

            hasDefinition = tsData != null;
        }

        return hasDefinition;
    }

    //when user reloads the tokens we should also check XML for any files
    public void clearCheckTimes()
    {
        assetChecked.clear();
    }

    public boolean hasTokenView(Token token, String type)
    {
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .equalTo("instanceKey", token.getTSKey())
                    .findFirst();

            return (tsData != null && tsData.getViewList().size() > 0);
        }
    }

    public String getTokenView(Token token, String type)
    {
        String viewHTML = "";
        TokenDefinition td = getAssetDefinition(token);
        if (td != null)
        {
            viewHTML = td.getTokenView(type);
        }

        return viewHTML;
    }

    public String getTokenViewStyle(Token token, String type)
    {
        String styleData = "";
        TokenDefinition td = getAssetDefinition(token);
        if (td != null)
        {
            styleData = td.getTokenViewStyle(type);
        }

        return styleData;
    }

    public List<Attribute> getTokenViewLocalAttributes(Token token)
    {
        TokenDefinition td = getAssetDefinition(token);
        List<Attribute> results = new ArrayList<>();
        if (td != null)
        {
            Map<String, Attribute> attrMap = td.getTokenViewLocalAttributes();
            results.addAll(attrMap.values());
        }

        return results;
    }

    public Map<String, TSAction> getTokenFunctionMap(Token token)
    {
        if (token.getInterfaceSpec() == ContractType.ATTESTATION)
        {
            return getAttestationFunctionMap(token);
        }

        TokenDefinition td = getAssetDefinition(token);
        if (td != null)
        {
            return td.getActions();
        }
        else
        {
            return null;
        }
    }

    public List<Attribute> getLocalAttributes(TokenDefinition td, Map<BigInteger, List<String>> availableActions)
    {
        List<Attribute> attrs = new ArrayList<>();
        if (td != null)
        {
            for (Map.Entry<BigInteger, List<String>> availableAction : availableActions.entrySet())
            {
                attrs.addAll(getLocalAttributes(td, availableAction.getValue()));
            }
        }

        return attrs;
    }

    private List<Attribute> getLocalAttributes(TokenDefinition td, List<String> actions)
    {
        List<Attribute> attrs = new ArrayList<>();
        for (Map.Entry<String, TSAction> action : td.getActions().entrySet())
        {
            if (!actions.contains(action.getKey()) || action.getValue().attributes == null)
            {
                continue;
            }
            attrs.addAll(action.getValue().attributes.values());
        }

        return attrs;
    }

    /**
     * Build a map of all available tokenIds to a list of available functions for that tokenId
     *
     * @param token
     * @return map of unique tokenIds to lists of allowed functions for that ID - note that we allow the function to be displayed if it has a denial message
     */
    public Single<Map<BigInteger, List<String>>> fetchFunctionMap(Token token, @NotNull List<BigInteger> tokenIds, ContractType type)
    {
        return Single.fromCallable(() -> {
            ActionModifier requiredActionModifier = type == ContractType.ATTESTATION ? ActionModifier.ATTESTATION : ActionModifier.NONE;
            Map<BigInteger, List<String>> validActions = new HashMap<>();
            TokenDefinition td = getAssetDefinition(token);
            if (td != null)
            {
                Map<String, TSAction> actions = td.getActions();
                //first gather all attrs required - do this so if there's multiple actions using the same attribute for a tokenId we aren't fetching the value repeatedly
                List<String> requiredAttrNames = getRequiredAttributeNames(actions, td);
                Map<BigInteger, Map<String, TokenScriptResult.Attribute>> attrResults   // Map of attribute results vs tokenId
                        = getRequiredAttributeResults(requiredAttrNames, tokenIds, td, token); // Map of all required attribute values vs all the tokenIds

                for (BigInteger tokenId : tokenIds)
                {
                    for (String actionName : actions.keySet())
                    {
                        TSAction action = actions.get(actionName);
                        if (action == null || action.modifier != requiredActionModifier)
                        {
                            continue; //do not include attestations if this isn't an attestation fetch
                        }

                        TSSelection selection = action.exclude != null ? td.getSelection(action.exclude) : null;
                        if (selection == null)
                        {
                            if (!validActions.containsKey(tokenId))
                                validActions.put(tokenId, new ArrayList<>());
                            validActions.get(tokenId).add(actionName);
                        }
                        else
                        {
                            //get required Attribute Results for this tokenId & selection
                            List<String> requiredAttributeNames = selection.getRequiredAttrs();
                            Map<String, TokenScriptResult.Attribute> idAttrResults = getAttributeResultsForTokenIds(attrResults, requiredAttributeNames, tokenId);
                            addIntrinsicAttributes(idAttrResults, token, tokenId); //adding intrinsic attributes eg ownerAddress, tokenId, contractAddress

                            //Now evaluate the selection
                            boolean exclude = EvaluateSelection.evaluate(selection.head, idAttrResults);
                            if (!exclude || selection.denialMessage != null)
                            {
                                if (!validActions.containsKey(tokenId))
                                    validActions.put(tokenId, new ArrayList<>());
                                validActions.get(tokenId).add(actionName);
                            }
                        }
                    }
                }
            }

            return validActions;
        });
    }

    private void addIntrinsicAttributes(Map<String, TokenScriptResult.Attribute> attrs, Token token, BigInteger tokenId)
    {
        //add tokenId, ownerAddress & contractAddress
        attrs.put("tokenId", new TokenScriptResult.Attribute("tokenId", "tokenId", tokenId, tokenId.toString(10)));
        attrs.put("ownerAddress", new TokenScriptResult.Attribute("ownerAddress", "ownerAddress", BigInteger.ZERO, token.getWallet()));
        attrs.put("contractAddress", new TokenScriptResult.Attribute("contractAddress", "contractAddress", BigInteger.ZERO, token.getAddress()));
    }

    public String checkFunctionDenied(Token token, String actionName, List<BigInteger> tokenIds)
    {
        String denialMessage = null;
        TokenDefinition td = getAssetDefinition(token);
        if (td != null)
        {
            BigInteger tokenId = (tokenIds != null && tokenIds.size() > 0) ? tokenIds.get(0) : BigInteger.ZERO;
            TSAction action = td.actions.get(actionName);
            TSSelection selection = action.exclude != null ? td.getSelection(action.exclude) : null;
            if (selection != null)
            {
                //gather list of attribute results
                List<String> requiredAttrs = selection.getRequiredAttrs();
                //resolve all these attrs
                Map<String, TokenScriptResult.Attribute> attrs = new HashMap<>();
                //get results
                for (String attrId : requiredAttrs)
                {
                    Attribute attr = td.attributes.get(attrId);
                    if (attr == null) continue;
                    TokenScriptResult.Attribute attrResult = tokenscriptUtility.fetchAttrResult(token, attr, tokenId, td, this, ViewType.VIEW).blockingGet();
                    if (attrResult != null) attrs.put(attrId, attrResult);
                }

                addIntrinsicAttributes(attrs, token, tokenId);

                boolean exclude = EvaluateSelection.evaluate(selection.head, attrs);
                if (exclude && !TextUtils.isEmpty(selection.denialMessage))
                {
                    denialMessage = selection.denialMessage;
                }
            }
        }

        return denialMessage;
    }

    private Map<String, TokenScriptResult.Attribute> getAttributeResultsForTokenIds(Map<BigInteger, Map<String, TokenScriptResult.Attribute>> attrResults, List<String> requiredAttributeNames,
                                                                                    BigInteger tokenId)
    {
        Map<String, TokenScriptResult.Attribute> results = new HashMap<>();
        if (!attrResults.containsKey(tokenId)) return results; //check values

        for (String attributeName : requiredAttributeNames)
        {
            results.put(attributeName, attrResults.get(tokenId).get(attributeName));
        }

        return results;
    }

    private Map<BigInteger, Map<String, TokenScriptResult.Attribute>> getRequiredAttributeResults(List<String> requiredAttrNames, List<BigInteger> tokenIds, TokenDefinition td, Token token)
    {
        Map<BigInteger, Map<String, TokenScriptResult.Attribute>> resultSet = new HashMap<>();
        for (BigInteger tokenId : tokenIds)
        {
            for (String attrName : requiredAttrNames)
            {
                Attribute attr = td.attributes.get(attrName);
                if (attr == null) continue;
                TokenScriptResult.Attribute attrResult = tokenscriptUtility.fetchAttrResult(token, attr, tokenId, td, this, ViewType.VIEW).blockingGet();
                if (attrResult != null)
                {
                    Map<String, TokenScriptResult.Attribute> tokenIdMap = resultSet.get(tokenId);
                    if (tokenIdMap == null)
                    {
                        tokenIdMap = new HashMap<>();
                        resultSet.put(tokenId, tokenIdMap);
                    }
                    tokenIdMap.put(attrName, attrResult);
                }
            }
        }

        return resultSet;
    }

    private List<String> getRequiredAttributeNames(Map<String, TSAction> actions, TokenDefinition td)
    {
        List<String> requiredAttrs = new ArrayList<>();
        for (String actionName : actions.keySet())
        {
            TSAction action = actions.get(actionName);
            TSSelection selection = action.exclude != null ? td.getSelection(action.exclude) : null;
            if (selection != null)
            {
                List<String> attrNames = selection.getRequiredAttrs();
                for (String attrName : attrNames)
                {
                    if (!requiredAttrs.contains(attrName))
                        requiredAttrs.add(attrName);
                }
            }
        }

        return requiredAttrs;
    }

    @Override
    public void parseMessage(ParseResultId parseResult)
    {
        switch (parseResult)
        {
            case PARSER_OUT_OF_DATE:
                HomeActivity.setUpdatePrompt();
                break;
            case XML_OUT_OF_DATE:
                break;
            case OK:
                break;
        }
    }

    private FileObserver startFileListener(String path)
    {
        FileObserver observer = new FileObserver(path)
        {
            private final String listenerPath = path;

            @Override
            public void onEvent(int event, @Nullable String file)
            {
                //watch for new files and file change
                switch (event)
                {
                    case CREATE:
                        //if this file already exists then wait for the modify
                        File checkFile = new File(listenerPath, file);
                        if (checkFile.exists() && checkFile.canRead())
                        {
                            break;
                        }
                    case MODIFY:
                        try
                        {
                            if (file.contains(TS_EXTENSION))
                            {
                                final File newTsFile = new File(listenerPath, file);
                                handleNewTSFile(newTsFile)
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(td -> notifyNewScript(td, newTsFile), this::onScriptError)
                                        .isDisposed();
                            }
                        }
                        catch (Exception e)
                        {
                            if (homeMessenger != null)
                                homeMessenger.tokenScriptError(e.getMessage());
                        }
                        break;
                    default:
                        break;
                }
            }

            private void onScriptError(Throwable throwable)
            {
                Timber.e(throwable);
            }

            private void notifyNewScript(TokenDefinition tokenDefinition, File file)
            {
                if (!TextUtils.isEmpty(tokenDefinition.holdingToken))
                {
                    notificationService.DisplayNotification("Definition Updated", file.getName(),
                            NotificationCompat.PRIORITY_MAX);
                    List<ContractLocator> originContracts = getOriginContracts(tokenDefinition);
                    for (ContractLocator cl : originContracts)
                    {
                        tokensService.addUnknownTokenToCheck(new ContractAddress(cl.chainId, cl.address));
                    }
                }
            }
        };

        observer.startWatching();

        return observer;
    }

    public Single<XMLDsigDescriptor> getSignatureData(Token token)
    {
        TokenScriptFile tsf = getTokenScriptFile(token);
        return getSignatureData(tsf);
    }

    public Single<XMLDsigDescriptor> getSignatureData(long chainId, String contractAddress)
    {
        TokenScriptFile tsf = getTokenScriptFile(chainId, contractAddress);
        return getSignatureData(tsf);
    }

    private Single<XMLDsigDescriptor> getSignatureData(TokenScriptFile tsf)
    {
        return Single.fromCallable(() -> {
            XMLDsigDescriptor sigDescriptor = new XMLDsigDescriptor();
            sigDescriptor.result = "fail";
            sigDescriptor.type = SigReturnType.NO_TOKENSCRIPT;

            if (tsf != null && tsf.exists())
            {
                String hash = tsf.calcMD5();
                XMLDsigDescriptor sig = getCertificateFromRealm(hash);
                if (sig == null || (sig.result != null && sig.result.equalsIgnoreCase("fail")))
                {
                    sig = alphaWalletService.checkTokenScriptSignature(tsf);
                    tsf.determineSignatureType(sig);
                    storeCertificateData(hash, sig);
                }
                sigDescriptor = sig;
            }

            return sigDescriptor;
        });
    }

    //Database functions
    private String functionKey(ContractAddress cAddr, BigInteger tokenId, String attrId)
    {
        //produce a unique key for this. token address, token Id, chainId
        return cAddr.address + "-" + tokenId.toString(Character.MAX_RADIX) + "-" + cAddr.chainId + "-" + attrId + "-func-key";
    }

    @Override
    public TransactionResult getFunctionResult(ContractAddress contract, Attribute attr, BigInteger tokenId)
    {
        TransactionResult tr = new TransactionResult(contract.chainId, contract.address, tokenId, attr);
        String dataBaseKey = functionKey(contract, tokenId, attr.name);
        try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
        {
            RealmAuxData realmToken = realm.where(RealmAuxData.class)
                    .equalTo("instanceKey", dataBaseKey)
                    .equalTo("chainId", contract.chainId)
                    .findFirst();

            if (realmToken != null)
            {
                tr.resultTime = realmToken.getResultTime();
                tr.result = realmToken.getResult();
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return tr;
    }

    @Override
    public TransactionResult storeAuxData(String walletAddress, TransactionResult tResult)
    {
        if (tokensService.getCurrentAddress() == null || !Utils.isAddressValid(tokensService.getCurrentAddress()))
            return tResult;
        if (tResult.result == null || tResult.resultTime < 0) return tResult;
        try (Realm realm = realmManager.getRealmInstance(walletAddress))
        {
            ContractAddress cAddr = new ContractAddress(tResult.contractChainId, tResult.contractAddress);
            String databaseKey = functionKey(cAddr, tResult.tokenId, tResult.attrId);
            realm.executeTransaction(r -> {
                RealmAuxData realmToken = r.where(RealmAuxData.class)
                        .equalTo("instanceKey", databaseKey)
                        .equalTo("chainId", tResult.contractChainId)
                        .findFirst();

                if (realmToken == null)
                {
                    createAuxData(r, tResult, databaseKey);
                }
                else if (tResult.result != null)
                {
                    realmToken.setResult(tResult.result);
                    realmToken.setResultTime(tResult.resultTime);
                    realmToken.setResultReceivedTime(System.currentTimeMillis());
                }
            });
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return tResult;
    }

    private void updateEventBlockTimes()
    {
        try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
        {
            RealmResults<RealmAuxData> realmEvents = realm.where(RealmAuxData.class)
                    .endsWith("instanceKey", "-eventBlock")
                    .sort("resultTime", Sort.ASCENDING)
                    .findAll();

            for (RealmAuxData eventData : realmEvents)
            {
                updateEventList(eventData);
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }
    }

    /**
     * For debugging & testing
     */
    @Keep
    private void deleteAllEventData()
    {
        //delete all realm event/attribute result data
        try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
        {
            realm.executeTransactionAsync(r -> {
                RealmResults<RealmAuxData> realmEvents = r.where(RealmAuxData.class)
                        .findAll();
                realmEvents.deleteAllFromRealm();
            });
        }
        catch (Exception e)
        {
            //
        }

        //Delete all tokenscript data
        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            realm.executeTransactionAsync(r -> {
                RealmResults<RealmTokenScriptData> rd = r.where(RealmTokenScriptData.class)
                        .findAll();

                RealmResults<RealmCertificateData> realmCert = r.where(RealmCertificateData.class)
                        .findAll();

                rd.deleteAllFromRealm();
                realmCert.deleteAllFromRealm();
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    private void updateEventList(RealmAuxData eventData)
    {
        String[] contractDetails = eventData.getInstanceKey().split("-");
        if (contractDetails.length != 5) return;
        String eventAddress = contractDetails[0];
        long chainId = Long.parseLong(contractDetails[1]);
        String eventId = eventData.getFunctionId();

        String eventKey = EventDefinition.getEventKey(chainId, eventAddress, eventId, null);
        EventDefinition ev = eventList.get(eventKey);
        if (ev != null)
        {
            ev.readBlock = new BigInteger(eventData.getResult(), 16).add(BigInteger.ONE); // add one so we don't pick up the same event again
        }
    }

    private void createAuxData(Realm realm, TransactionResult tResult, String dataBaseKey)
    {
        try
        {
            RealmAuxData realmData = realm.createObject(RealmAuxData.class, dataBaseKey);
            realmData.setResultTime(tResult.resultTime);
            realmData.setResult(tResult.result);
            realmData.setChainId(tResult.contractChainId);
            realmData.setFunctionId(tResult.method);
            realmData.setTokenId(tResult.tokenId.toString(Character.MAX_RADIX));
            realmData.setResultReceivedTime(System.currentTimeMillis());
        }
        catch (RealmPrimaryKeyConstraintException e)
        {
            //in theory we should never see this
            Timber.e(e);
        }
    }


    //private Token

    private void addOpenSeaAttributes(StringBuilder attrs, Token token, BigInteger tokenId)
    {
        NFTAsset tokenAsset = token.getAssetForToken(tokenId.toString());
        if (tokenAsset == null) return;

        try
        {
            //add all asset IDs
            if (tokenAsset.getBackgroundColor() != null)
                TokenScriptResult.addPair(attrs, "background_colour", URLEncoder.encode(tokenAsset.getBackgroundColor(), "utf-8"));
            if (tokenAsset.getThumbnail() != null)
                TokenScriptResult.addPair(attrs, "image_preview_url", URLEncoder.encode(tokenAsset.getThumbnail(), "utf-8"));
            if (tokenAsset.getDescription() != null)
                TokenScriptResult.addPair(attrs, "description", URLEncoder.encode(tokenAsset.getDescription(), "utf-8"));
            if (tokenAsset.getExternalLink() != null)
                TokenScriptResult.addPair(attrs, "external_link", URLEncoder.encode(tokenAsset.getExternalLink(), "utf-8"));
            //if (tokenAsset.getTraits() != null) TokenScriptResult.addPair(attrs, "traits", tokenAsset.getTraits());
            if (tokenAsset.getName() != null)
                TokenScriptResult.addPair(attrs, "metadata_name", URLEncoder.encode(tokenAsset.getName(), "utf-8"));
        }
        catch (UnsupportedEncodingException e)
        {
            //
        }
    }

    public StringBuilder getTokenAttrs(Token token, BigInteger tokenId, int count)
    {
        StringBuilder attrs = new StringBuilder();

        TokenDefinition definition = getAssetDefinition(token);
        String label = token.getTokenTitle();
        if (definition != null && definition.getTokenName(1) != null)
        {
            label = definition.getTokenName(1);
        }
        TokenScriptResult.addPair(attrs, "name", token.tokenInfo.name);
        TokenScriptResult.addPair(attrs, "label", label);
        TokenScriptResult.addPair(attrs, "symbol", token.getSymbol());
        TokenScriptResult.addPair(attrs, "_count", BigInteger.valueOf(count));
        TokenScriptResult.addPair(attrs, "contractAddress", token.tokenInfo.address);
        TokenScriptResult.addPair(attrs, "chainId", BigInteger.valueOf(token.tokenInfo.chainId));
        TokenScriptResult.addPair(attrs, "tokenId", tokenId);
        TokenScriptResult.addPair(attrs, "ownerAddress", token.getWallet());

        if (token.isNonFungible())
        {
            addOpenSeaAttributes(attrs, token, tokenId);
        }

        if (token.isEthereum())
        {
            TokenScriptResult.addPair(attrs, "balance", token.balance.toString());
        }

        return attrs;
    }

    /**
     * Get all the magic values - eg native crypto balances for all chains
     *
     * @return
     */
    public String getMagicValuesForInjection(long chainId) throws Exception
    {
        String walletBalance = "walletBalance";
        String prefix = "web3.eth";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        Token nativeCurrency = tokensService.getToken(chainId, tokensService.getCurrentAddress());
        sb.append(prefix).append(" = {\n").append(walletBalance).append(": ").append(nativeCurrency.balance.toString()).append("\n}\n");

        List<Token> nativeCurrencies = tokensService.getAllAtAddress(tokensService.getCurrentAddress());
        for (Token currency : nativeCurrencies)
        {
            sb.append(prefix).append("_").append(currency.tokenInfo.chainId).append(" = {\n").append(walletBalance).append(": ").append(currency.balance.toString()).append("\n}\n");
        }

        sb.append("\n\n");

        return sb.toString();
    }

    public void clearResultMap()
    {
        tokenscriptUtility.clearParseMaps();
    }

    public Observable<TokenScriptResult.Attribute> resolveAttrs(Token token, TokenDefinition td, BigInteger tokenId, List<Attribute> extraAttrs, ViewType itemView)
    {
        TokenDefinition definition = td != null ? td : getAssetDefinition(token);
        ContractAddress cAddr = new ContractAddress(token.tokenInfo.chainId, token.tokenInfo.address);
        if (definition == null)
            return Observable.fromCallable(() -> new TokenScriptResult.Attribute("RAttrs", "", BigInteger.ZERO, ""));

        definition.context = new TokenscriptContext();
        definition.context.cAddr = cAddr;
        definition.context.attrInterface = this;

        List<Attribute> attrList = new ArrayList<>(definition.attributes.values());
        if (extraAttrs != null) attrList.addAll(extraAttrs);

        return resolveAttrs(token, tokenId, definition, attrList, itemView);
    }

    /*private Function formValidation(EasAttestation attn)
    {
        //Commented out
        byte[] signature = attn.getSignatureBytes();

        List<Type> inputParams = Arrays.asList(attn.getAttestationCore(), new DynamicBytes(signature));
        DefaultFunctionEncoder dfe = new DefaultFunctionEncoder();

        List<Type> inputParam = Collections.singletonList(attn.getAttestationCore());
        String hex = dfe.encodeParameters(inputParam);
        //trim struct definition
        hex = hex.substring(0x40);

        List<TypeReference<?>> returnTypes = Arrays.asList(new TypeReference<Bool>() { }, new TypeReference<Address>() { }, new TypeReference<Address>() { }, new TypeReference<Bool>() { }, new TypeReference<Uint64>() { });
        Function testValidation = new Function("verifyEASAttestation",
                inputParams,
                returnTypes);

        String contractAddress = "0xeAC4F618232B5cA1C895B6e5468363fdd128E873";

        String result = tokenscriptUtility.callSmartContract(attn.getChainId(), contractAddress, testValidation);
        List<Type> values = FunctionReturnDecoder.decode(result, testValidation.getOutputParameters());

        inputParams.clear();
        //(string memory eventId, string memory ticketId, uint8 ticketClass, bytes memory commitment)
        returnTypes = Arrays.asList(new TypeReference<Utf8String>() { }, new TypeReference<Utf8String>() { }, new TypeReference<Uint8>() { }, new TypeReference<DynamicBytes>() { });


        //OK. Try decode
        testValidation = new Function("decodeAttestationData",
                inputParam,
                returnTypes);


        return testValidation;
    }*/

    public List<TokenScriptResult.Attribute> getAttestationAttrs(Token token, TSAction action, String attnId)
    {
        List<TokenScriptResult.Attribute> attrs = new ArrayList<>();
        Attestation att = (action != null && action.modifier == ActionModifier.ATTESTATION) ? (Attestation) tokensService.getAttestation(token.tokenInfo.chainId, token.getAddress(), attnId) : null;
        if (att != null)
        {
            if (att.isEAS())
            {
                //can we rebuild the EasAttestation?
                DefaultFunctionEncoder dfe = new DefaultFunctionEncoder();
                EasAttestation easAttestation = att.getEasAttestation();
                List<Type> inputParam = Collections.singletonList(easAttestation.getAttestationCore());
                String coreAttestationHex = "0x" + dfe.encodeParameters(inputParam).substring(0x40);
                attrs.add(new TokenScriptResult.Attribute("attestation", "attestation", BigInteger.ZERO, coreAttestationHex));
                //also require signature
                String signatureBytes = Numeric.toHexString(easAttestation.getSignatureBytes());
                attrs.add(new TokenScriptResult.Attribute("attestationSig", "attestationSig", BigInteger.ZERO, signatureBytes));
            }
            else
            {
                attrs.add(new TokenScriptResult.Attribute("attestation", "attestation", BigInteger.ZERO, Numeric.toHexString(att.getAttestation())));
            }
        }

        return attrs;
    }

    public Map<String, TSAction> getAttestationFunctionMap(Token att)
    {
        TokenDefinition td = getAssetDefinition(att);
        Map<String, TSAction> actions = new HashMap<>();
        if (att != null && td != null)
        {
            //got attestation, fetch all functions related to attestation
            for (Map.Entry<String, TSAction> entry : td.actions.entrySet())
            {
                if (entry.getValue().modifier == ActionModifier.ATTESTATION)
                {
                    actions.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return actions;
    }

    private Observable<TokenScriptResult.Attribute> resolveAttrs(Token token, BigInteger tokenId, TokenDefinition td,
                                                                 List<Attribute> attrList, ViewType itemView)
    {
        tokenscriptUtility.buildAttrMap(attrList);
        return Observable.fromIterable(attrList)
                .flatMap(attr -> tokenscriptUtility.fetchAttrResult(token, attr, tokenId,
                        td, this, itemView).toObservable());
    }

    public Observable<TokenScriptResult.Attribute> resolveAttrs(Token token, List<BigInteger> tokenIds, List<Attribute> extraAttrs)
    {
        TokenDefinition definition = getAssetDefinition(token);
        if (definition == null)
        {
            return Observable.fromCallable(() -> new TokenScriptResult.Attribute("", "", BigInteger.ZERO, ""));
        }
        //pre-fill tokenIds
        for (Attribute attrType : definition.attributes.values())
        {
            resolveTokenIds(attrType, tokenIds);
        }

        //TODO: store transaction fetch time for multiple tokenIds

        return resolveAttrs(token, definition, tokenIds.get(0), extraAttrs, ViewType.VIEW);
    }

    private void resolveTokenIds(Attribute attrType, List<BigInteger> tokenIds)
    {
        if (attrType.function == null) return;

        for (MethodArg arg : attrType.function.parameters)
        {
            int index = arg.getTokenIndex();
            if (arg.isTokenId() && index >= 0 && index < tokenIds.size())
            {
                arg.element.value = tokenIds.get(index).toString();
            }
        }
    }

    private List<String> getLocalTSMLFiles()
    {
        List<String> localTSMLFilesStr = new ArrayList<>();
        AssetManager mgr = context.getResources().getAssets();
        try
        {
            String[] filelist = mgr.list("");
            for (String file : filelist)
            {
                if (file.contains("tsml"))
                {
                    localTSMLFilesStr.add(file);
                }
            }

        }
        catch (IOException e)
        {
            Timber.e(e);
        }

        return localTSMLFilesStr;
    }

    public String generateTransactionPayload(Token token, BigInteger tokenId, FunctionDefinition def)
    {
        TokenDefinition td = getAssetDefinition(token);
        if (td == null) return "";
        Function function = tokenscriptUtility.generateTransactionFunction(token, tokenId, td, def, this);
        if (function.getInputParameters() == null)
        {
            return null;
        }
        else
        {
            return FunctionEncoder.encode(function);
        }
    }

    /**
     * Clear the currently cached definition. This forces the service to reload the definition so it's clean for the next usage.
     */
    public void clearCache()
    {
        cachedDefinition = null;
    }

    public ContractLocator getHoldingContract(String importFileName)
    {
        ContractLocator cr = null;

        try (Realm realm = realmManager.getRealmInstance(ASSET_DEFINITION_DB))
        {
            RealmTokenScriptData tsData = realm.where(RealmTokenScriptData.class)
                    .contains("filePath", importFileName)
                    .findFirst();

            if (tsData != null)
            {
                cr = new ContractLocator(tsData.getOriginTokenAddress(), tsData.getChainId());
            }
        }

        return cr;
    }

    public String convertInputValue(Attribute attr, String valueFromInput)
    {
        return tokenscriptUtility.convertInputValue(attr, valueFromInput);
    }

    public String resolveReference(@NotNull Token token, TSAction action, TokenscriptElement arg, BigInteger tokenId)
    {
        TokenDefinition td = getAssetDefinition(token);
        return tokenscriptUtility.resolveReference(token, arg, tokenId, td, this);
    }

    public void setErrorCallback(FragmentMessenger callback)
    {
        homeMessenger = callback;
    }

    /**
     * Using a file search method rather than the pre-parsed method.
     * This lets us catch bad tokenscripts and report on errors.
     *
     * @return List of Tokenscripts with details
     */
    public Single<List<TokenLocator>> getAllTokenDefinitions(boolean refresh)
    {
        return Single.fromCallable(() -> {
            if (refresh)
            {
                loadAssetScripts();
            }
            waitForAssets();
            List<TokenLocator> tokenLocators = new ArrayList<>();
            List<File> fileList = buildFileList();
            Collections.reverse(fileList); // the manager expects the priority order in reverse - lowest priority first
            Observable.fromIterable(fileList)
                    .filter(File::isFile)
                    .filter(this::allowableExtension)
                    .filter(File::canRead)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .blockingForEach(file -> {
                        try
                        {
                            FileInputStream input = new FileInputStream(file);
                            TokenDefinition tokenDef = parseFile(input);
                            ContractInfo origins = tokenDef.contracts.get(tokenDef.holdingToken);
                            if (origins.addresses.size() > 0)
                            {
                                TokenScriptFile tsf = new TokenScriptFile(context, file.getAbsolutePath());
                                tokenLocators.add(new TokenLocator(tokenDef.getTokenName(1), origins, tsf));
                            }
                        } // TODO: Catch specific tokenscript parse errors to report tokenscript errors.
                        catch (SAXException e)
                        {
                            //not a legal XML TokenScript file. Just ignore
                        }
                        catch (Exception e)
                        {
                            TokenScriptFile tsf = new TokenScriptFile(context, file.getAbsolutePath());
                            ContractInfo contractInfo = new ContractInfo("Contract Type", new HashMap<>());
                            StringWriter stackTrace = new StringWriter();
                            Timber.e(new PrintWriter(stackTrace).toString());
                            tokenLocators.add(new TokenLocator(file.getName(), contractInfo, tsf, true, stackTrace.toString()));
                        }
                    });

            return tokenLocators;
        });
    }

    public Single<TokenDefinition> checkServerForScript(Token token, MutableLiveData<Boolean> updateFlag)
    {
        TokenScriptFile tf = getTokenScriptFile(token);

        if ((tf != null && !TextUtils.isEmpty(tf.getName())) && !isInSecureZone(tf))
        {
            return Single.fromCallable(TokenDefinition::new); //early return for debug script check
        }

        //try the contractURI, then server
        return fetchTokenScriptFromContract(token, updateFlag)
                .flatMap(file -> tryServerIfRequired(file, token.getAddress().toLowerCase()))
                .flatMap(this::cacheSignature)
                .flatMap(this::handleNewTSFile)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public void storeTokenViewHeight(long chainId, String address, int listViewHeight)
    {
        try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
        {
            realm.executeTransactionAsync(r -> {
                TokenScriptFile tsf = getTokenScriptFile(chainId, address);
                if (tsf == null || !tsf.exists()) return;
                String hash = tsf.calcMD5();
                String databaseKey = tokenSizeDBKey(chainId, address);

                RealmAuxData realmToken = r.where(RealmAuxData.class)
                        .equalTo("instanceKey", databaseKey)
                        .equalTo("chainId", chainId)
                        .findFirst();

                if (realmToken == null)
                {
                    realmToken = r.createObject(RealmAuxData.class, databaseKey);
                }
                realmToken.setChainId(chainId);
                realmToken.setResult(hash);
                realmToken.setResultTime(listViewHeight);
            });
        }
    }

    public String getTokenImageUrl(long networkId, String address)
    {
        String url = "";
        String instanceKey = address.toLowerCase() + "-" + networkId;
        try (Realm realm = realmManager.getRealmInstance(IMAGES_DB))
        {
            RealmAuxData instance = realm.where(RealmAuxData.class)
                    .equalTo("instanceKey", instanceKey)
                    .findFirst();

            if (instance != null)
            {
                url = instance.getResult();
            }
        }
        catch (Exception ex)
        {
            Timber.e(ex);
        }

        return url;
    }

    public Pair<String, Boolean> getFallbackUrlForToken(Token token)
    {
        boolean storedOverride = false;
        String correctedAddr = Keys.toChecksumAddress(token.getAddress());

        String tURL = getTokenImageUrl(token.tokenInfo.chainId, token.getAddress());
        if (TextUtils.isEmpty(tURL))
        {
            tURL = Utils.getTWTokenImageUrl(token.tokenInfo.chainId, correctedAddr);
        }
        else
        {
            storedOverride = true;
        }

        return new Pair<>(tURL, storedOverride);
    }

    public void storeImageUrl(long chainId, String imageUrl)
    {
        String tokenAddress = Utils.getTokenAddrFromAWUrl(imageUrl);
        if (!TextUtils.isEmpty(tokenAddress))
        {
            tokensService.addTokenImageUrl(chainId, tokenAddress, imageUrl);
        }
    }

    public Single<Integer> fetchViewHeight(long chainId, String address)
    {
        return Single.fromCallable(() -> {
            try (Realm realm = realmManager.getRealmInstance(tokensService.getCurrentAddress()))
            {
                //determine hash
                TokenScriptFile tsf = getTokenScriptFile(chainId, address);
                if (tsf == null || !tsf.exists()) return 0;
                String hash = tsf.calcMD5();
                String databaseKey = tokenSizeDBKey(chainId, address);

                RealmAuxData realmToken = realm.where(RealmAuxData.class)
                        .equalTo("instanceKey", databaseKey)
                        .equalTo("chainId", chainId)
                        .findFirst();

                if (realmToken == null)
                {
                    return 0;
                }

                if (hash.equals(realmToken.getResult()))
                {
                    //can use this height
                    return (int) realmToken.getResultTime();
                }
            }
            catch (Exception e)
            {
                Timber.e(e);
            }

            return 0;
        });
    }

    private String tokenSizeDBKey(long chainId, String address)
    {
        return "szkey-" + chainId + "-" + address.toLowerCase();
    }

    public Realm getEventRealm()
    {
        return realmManager.getRealmInstance(tokensService.getCurrentAddress());
    }

    // For testing only
    private void deleteAWRealm()
    {
        try (Realm realm = realmManager.getRealmInstance(IMAGES_DB))
        {
            realm.executeTransactionAsync(r -> {
                RealmResults<RealmAuxData> instance = r.where(RealmAuxData.class)
                        .findAll();

                if (instance != null)
                {
                    instance.deleteAllFromRealm();
                }
            });
        }
    }

    public Attestation validateAttestation(String attestation, TokenInfo tInfo)
    {
        TokenDefinition td = getDefinition(getTSDataKeyTemp(tInfo.chainId, tInfo.address));//getDefinition(tInfo.chainId, tInfo.address);
        Attestation att = null;

        if (td != null)
        {
            NetworkInfo networkInfo = EthereumNetworkBase.getNetworkByChain(tInfo.chainId);
            att = new Attestation(tInfo, networkInfo.name, Numeric.hexStringToByteArray(attestation));
            att.setTokenWallet(tokensService.getCurrentAddress());

            //call validation function and get details
            AttestationDefinition definitionAtt = td.getAttestation();
            //can we get the details?

            if (definitionAtt != null && definitionAtt.function != null)
            {
                //pull return type
                FunctionDefinition fd = definitionAtt.function;
                //add attestation to attr map
                //call function
                org.web3j.abi.datatypes.Function transaction = tokenscriptUtility.generateTransactionFunction(att, BigInteger.ZERO, td, fd, this);
                transaction = new Function(fd.method, transaction.getInputParameters(), td.getAttestationReturnTypes()); //set return types

                //call and handle result
                String result = tokenscriptUtility.callSmartContract(tInfo.chainId, tInfo.address, transaction);

                //break down result
                List<Type> values = FunctionReturnDecoder.decode(result, transaction.getOutputParameters());

                //interpret these values
                att.handleValidation(td.getValidation(values));
            }
        }

        return att;
    }

    public Attestation validateAttestation(EasAttestation attestation, String importedAttestation)
    {
        String recoverAttestationSigner = recoverSigner(attestation);

        //1. Validate signer via key attestation service (using UID).
        //call validate
        SchemaRecord schemaRecord = fetchSchemaRecord(attestation.getChainId(), getKeySchemaUID(attestation.getChainId()));
        boolean attestationValid = checkAttestationIssuer(schemaRecord, attestation.getChainId(), recoverAttestationSigner);

        //2. Decode the ABI encoded payload to pull out the info. ABI Decode the schema bytes
        //initially we need a hardcoded schema - this should be fetched from the schema record EAS contract
        //fetch the schema of the attestation
        SchemaRecord attestationSchema = fetchSchemaRecord(attestation.getChainId(), attestation.schema);
        //convert into functionDecode
        List<String> names = new ArrayList<>();
        List<Type> values = decodeAttestationData(attestation.data, attestationSchema.schema, names);

        NetworkInfo networkInfo = EthereumNetworkBase.getNetworkByChain(attestation.getChainId());

        TokenInfo tInfo = Utils.getDefaultAttestationInfo(attestation.getChainId(), getEASContract(attestation.chainId));
        Attestation localAttestation = new Attestation(tInfo, networkInfo.name, importedAttestation.getBytes(StandardCharsets.UTF_8));
        localAttestation.handleEASAttestation(attestation, names, values, attestationValid);

        String collectionHash = localAttestation.getAttestationCollectionId();

        //Now regenerate with the correct collectionId
        tInfo = Utils.getDefaultAttestationInfo(attestation.getChainId(), collectionHash);
        localAttestation = new Attestation(tInfo, networkInfo.name, importedAttestation.getBytes(StandardCharsets.UTF_8));
        localAttestation.handleEASAttestation(attestation, names, values, attestationValid);
        localAttestation.setTokenWallet(tokensService.getCurrentAddress());
        return localAttestation;
    }

    private String getDataValue(String key, List<String> names, List<Type> values)
    {
        Map<String, String> valueMap = new HashMap<>();
        for (int index = 0; index < names.size(); index++)
        {
            String name = names.get(index);
            Type<?> type = values.get(index);
            valueMap.put(name, type.toString());
        }

        return valueMap.get(key);
    }

    private List<Type> decodeAttestationData(String attestationData, @NonNull String decodeSchema, List<String> names)
    {
        List<TypeReference<?>> returnTypes = new ArrayList<TypeReference<?>>();
        //build decoder
        String[] typeData = decodeSchema.split(",");
        for (String typeElement : typeData)
        {
            String[] data = typeElement.split(" ");
            String type = data[0];
            String name = data[1];
            if (type.startsWith("uint") || type.startsWith("int"))
            {
                type = "uint";
            }
            else if (type.startsWith("bytes") && !type.equals("bytes"))
            {
                type = "bytes32";
            }

            TypeReference<?> tRef = null;

            switch (type)
            {
                case "uint":
                    tRef = new TypeReference<Uint256>() { };
                    break;
                case "address":
                    tRef = new TypeReference<Address>() { };
                    break;
                case "bytes32":
                    tRef = new TypeReference<Bytes32>() { };
                    break;
                case "string":
                    tRef = new TypeReference<Utf8String>() { };
                    break;
                case "bytes":
                    tRef = new TypeReference<DynamicBytes>() { };
                    break;
                case "bool":
                    tRef = new TypeReference<Bool>() { };
                    break;
                default:
                    break;
            }

            if (tRef != null)
            {
                returnTypes.add(tRef);
            }
            else
            {
                Timber.e("Unhandled type!");
                returnTypes.add(new TypeReference<Uint256>() { });
            }

            names.add(name);
        }

        //decode the schema and populate the Attestation element
        return FunctionReturnDecoder.decode(attestationData, org.web3j.abi.Utils.convert(returnTypes));
    }

    private SchemaRecord fetchSchemaRecord(long chainId, String schemaUID)
    {
        //1. Resolve UID. For now, just use default: This should be on a switch for chains
        String globalResolver = getEASSchemaContract(chainId);

        //format transaction to get key resolver
        Function getKeyResolver2 = new Function("getSchema",
                Collections.singletonList(new Bytes32(Numeric.hexStringToByteArray(schemaUID))),
                Collections.singletonList(new TypeReference<SchemaRecord>() {}));

        String result = tokenscriptUtility.callSmartContract(chainId, globalResolver, getKeyResolver2);
        List<Type> values = FunctionReturnDecoder.decode(result, getKeyResolver2.getOutputParameters());

        return (SchemaRecord)values.get(0);
    }

    private boolean checkAttestationIssuer(SchemaRecord schemaRecord, long chainId, String signer)
    {
        String rootKeyUID = getDefaultRootKeyUID(chainId);
        //pull the key resolver
        Address resolverAddr = schemaRecord.resolver;
        //call the resolver to test key validity
        Function validateKey = new Function("validateSignature",
                Arrays.asList((new Bytes32(Numeric.hexStringToByteArray(rootKeyUID))),
                        new Address(signer)),
                Collections.singletonList(new TypeReference<Bool>() {}));

        String result = tokenscriptUtility.callSmartContract(chainId, resolverAddr.getValue(), validateKey);
        List<Type> values = FunctionReturnDecoder.decode(result, validateKey.getOutputParameters());
        return ((Bool)values.get(0)).getValue();
    }

    private String recoverSigner(EasAttestation attestation)
    {
        String recoveredAddress = "";

        try
        {
            StructuredDataEncoder dataEncoder = new StructuredDataEncoder(attestation.getEIP712Attestation());
            byte[] hash = dataEncoder.hashStructuredData();
            byte[] r = Numeric.hexStringToByteArray(attestation.getR());
            byte[] s = Numeric.hexStringToByteArray(attestation.getS());
            byte v = (byte)(attestation.getV() & 0xFF);

            Sign.SignatureData sig = new Sign.SignatureData(v, r, s);

            BigInteger key = Sign.signedMessageHashToKey(hash, sig);
            recoveredAddress = "0x" + Keys.getAddress(key);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return recoveredAddress;
    }

    // NB Java 11 doesn't have support for switching on a 'long' :(
    public static String getEASContract(long chainId)
    {
        if (chainId == MAINNET_ID)
        {
            return "0xA1207F3BBa224E2c9c3c6D5aF63D0eb1582Ce587";
        }
        else if (chainId == ARBITRUM_MAIN_ID)
        {
            return "0xbD75f629A22Dc1ceD33dDA0b68c546A1c035c458";
        }
        else if (chainId == SEPOLIA_TESTNET_ID)
        {
            return "0xC2679fBD37d54388Ce493F1DB75320D236e1815e";
        }
        else
        {
            //Support Optimism Goerli (0xC2679fBD37d54388Ce493F1DB75320D236e1815e)
            return "";
        }
    }

    private String getEASSchemaContract(long chainId)
    {
        if (chainId == MAINNET_ID)
        {
            return "0xA7b39296258348C78294F95B872b282326A97BDF";
        }
        else if (chainId == ARBITRUM_MAIN_ID)
        {
            return "0xA310da9c5B885E7fb3fbA9D66E9Ba6Df512b78eB";
        }
        else if (chainId == SEPOLIA_TESTNET_ID)
        {
            return "0x0a7E2Ff54e76B8E6659aedc9103FB21c038050D0";
        }
        else
        {
            //Support Optimism Goerli (0x7b24C7f8AF365B4E308b6acb0A7dfc85d034Cb3f)
            return "";
        }
    }

    //UID of schema used for keys on each chain - the resolver is tied to this UID
    private String getKeySchemaUID(long chainId)
    {
        if (chainId == MAINNET_ID)
        {
            return "";
        }
        else if (chainId == ARBITRUM_MAIN_ID)
        {
            return "0x5f0437f7c1db1f8e575732ca52cc8ad899b3c9fe38b78b67ff4ba7c37a8bf3b4";
        }
        else if (chainId == SEPOLIA_TESTNET_ID)
        {
            return "0x4455598d3ec459c4af59335f7729fea0f50ced46cb1cd67914f5349d44142ec1";
        }
        else
        {
            //Support Optimism Goerli (0x7b24C7f8AF365B4E308b6acb0A7dfc85d034Cb3f)
            return "";
        }
    }

    // If not specified
    private String getDefaultRootKeyUID(long chainId)
    {
        if (chainId == MAINNET_ID)
        {
            return "";
        }
        else if (chainId == ARBITRUM_MAIN_ID)
        {
            return "0xe5c2bfd98a1b35573610b4e5a367bbcb5c736e42508a33fd6046bad63eaf18f9";
        }
        else if (chainId == SEPOLIA_TESTNET_ID)
        {
            return "0xee99de42f544fa9a47caaf8d4a4426c1104b6d7a9df7f661f892730f1b5b1e23";
        }
        else
        {
            //Support Optimism Goerli (0x7b24C7f8AF365B4E308b6acb0A7dfc85d034Cb3f)
            return "";
        }
    }
}
