package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.dataaccess.DataAccess;
import edu.harvard.iq.dataverse.dataaccess.ImageThumbConverter;
import edu.harvard.iq.dataverse.dataaccess.StorageIO;
import edu.harvard.iq.dataverse.dataset.DatasetUtil;
import edu.harvard.iq.dataverse.datavariable.DataVariable;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.DestroyDatasetCommand;
import edu.harvard.iq.dataverse.engine.command.impl.FinalizeDatasetPublicationCommand;
import edu.harvard.iq.dataverse.engine.command.impl.GetDatasetStorageSizeCommand;
import edu.harvard.iq.dataverse.export.ExportService;
import edu.harvard.iq.dataverse.harvest.server.OAIRecordServiceBean;
import edu.harvard.iq.dataverse.search.IndexServiceBean;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.workflows.WorkflowComment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Asynchronous;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.StoredProcedureQuery;
import javax.persistence.TypedQuery;
import org.apache.commons.lang3.RandomStringUtils;
import org.ocpsoft.common.util.Strings;

/**
 *
 * @author skraffmiller
 */


@Stateless
@Named
public class DatasetServiceBean implements java.io.Serializable {

    private static final Logger logger = Logger.getLogger(DatasetServiceBean.class.getCanonicalName());
    @EJB
    IndexServiceBean indexService;

    @EJB
    DOIEZIdServiceBean doiEZIdServiceBean;

    @EJB
    SettingsServiceBean settingsService;

    @EJB
    DatasetVersionServiceBean versionService;

    @EJB
    DvObjectServiceBean dvObjectService;

    @EJB
    AuthenticationServiceBean authentication;

    @EJB
    DataFileServiceBean fileService;

    @EJB
    PermissionServiceBean permissionService;

    @EJB
    OAIRecordServiceBean recordService;

    @EJB
    EjbDataverseEngine commandEngine;

    @EJB
    SystemConfig systemConfig;

    private static final SimpleDateFormat logFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss");

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    protected EntityManager em;

    public Dataset find(Object pk) {
        return em.find(Dataset.class, pk);
    }

    public List<Dataset> findByOwnerId(Long ownerId) {
        return findByOwnerId(ownerId, false);
    }

    public List<Dataset> findPublishedByOwnerId(Long ownerId) {
        return findByOwnerId(ownerId, true);
    }

    private List<Dataset> findByOwnerId(Long ownerId, boolean onlyPublished) {
        List<Dataset> retList = new ArrayList<>();
        TypedQuery<Dataset>  query = em.createNamedQuery("Dataset.findByOwnerId", Dataset.class);
        query.setParameter("ownerId", ownerId);
        if (!onlyPublished) {
            return query.getResultList();
        } else {
            for (Dataset ds : query.getResultList()) {
                if (ds.isReleased() && !ds.isDeaccessioned()) {
                    retList.add(ds);
                }
            }
            return retList;
        }
    }

    public List<Long> findIdsByOwnerId(Long ownerId) {
        return findIdsByOwnerId(ownerId, false);
    }

    private List<Long> findIdsByOwnerId(Long ownerId, boolean onlyPublished) {
        List<Long> retList = new ArrayList<>();
        if (!onlyPublished) {
            return em.createNamedQuery("Dataset.findIdByOwnerId")
                    .setParameter("ownerId", ownerId)
                    .getResultList();
        } else {
            List<Dataset> results = em.createNamedQuery("Dataset.findByOwnerId")
                    .setParameter("ownerId", ownerId).getResultList();
            for (Dataset ds : results) {
                if (ds.isReleased() && !ds.isDeaccessioned()) {
                    retList.add(ds.getId());
                }
            }
            return retList;
        }
    }

    public List<Dataset> findByCreatorId(Long creatorId) {
        return em.createNamedQuery("Dataset.findByCreatorId").setParameter("creatorId", creatorId).getResultList();
    }

    public List<Dataset> findByReleaseUserId(Long releaseUserId) {
        return em.createNamedQuery("Dataset.findByReleaseUserId").setParameter("releaseUserId", releaseUserId).getResultList();
    }

    public List<Dataset> filterByPidQuery(String filterQuery) {
        // finds only exact matches
        Dataset ds = findByGlobalId(filterQuery);
        List<Dataset> ret = new ArrayList<>();
        if (ds != null) ret.add(ds);


        /*
        List<Dataset> ret = em.createNamedQuery("Dataset.filterByPid", Dataset.class)
            .setParameter("affiliation", "%" + filterQuery.toLowerCase() + "%").getResultList();
        //logger.info("created native query: select o from Dataverse o where o.alias LIKE '" + filterQuery + "%' order by o.alias");
        logger.info("created named query");
        */
        if (ret != null) {
            logger.info("results list: "+ret.size()+" results.");
        }
        return ret;
    }

    public List<Dataset> findAll() {
        return em.createQuery("select object(o) from Dataset as o order by o.id", Dataset.class).getResultList();
    }

    public List<Long> findIdStale() {
        return em.createNamedQuery("Dataset.findIdStale").getResultList();
    }

     public List<Long> findIdStalePermission() {
        return em.createNamedQuery("Dataset.findIdStalePermission").getResultList();
    }

    public List<Long> findAllLocalDatasetIds() {
        return em.createQuery("SELECT o.id FROM Dataset o WHERE o.harvestedFrom IS null ORDER BY o.id", Long.class).getResultList();
    }

    public List<Long> findAllUnindexed() {
        return em.createQuery("SELECT o.id FROM Dataset o WHERE o.indexTime IS null ORDER BY o.id DESC", Long.class).getResultList();
    }

    //Used in datasets listcurationstatus API
    public List<Dataset> findAllUnpublished() {
        return em.createQuery("SELECT object(o) FROM Dataset o, DvObject d WHERE d.id=o.id and d.publicationDate IS null ORDER BY o.id ASC", Dataset.class).getResultList();
    }

    /**
     * For docs, see the equivalent method on the DataverseServiceBean.
     * @param numPartitions
     * @param partitionId
     * @param skipIndexed
     * @return a list of datasets
     * @see DataverseServiceBean#findAllOrSubset(long, long, boolean)
     */
    public List<Long> findAllOrSubset(long numPartitions, long partitionId, boolean skipIndexed) {
        if (numPartitions < 1) {
            long saneNumPartitions = 1;
            numPartitions = saneNumPartitions;
        }
        String skipClause = skipIndexed ? "AND o.indexTime is null " : "";
        TypedQuery<Long> typedQuery = em.createQuery("SELECT o.id FROM Dataset o WHERE MOD( o.id, :numPartitions) = :partitionId " +
                skipClause +
                "ORDER BY o.id", Long.class);
        typedQuery.setParameter("numPartitions", numPartitions);
        typedQuery.setParameter("partitionId", partitionId);
        return typedQuery.getResultList();
    }

        /**
     * For docs, see the equivalent method on the DataverseServiceBean.
     * @param numPartitions
     * @param partitionId
     * @param skipIndexed
     * @return a list of datasets
     * @see DataverseServiceBean#findAllOrSubset(long, long, boolean)
     */
    public List<Long> findAllOrSubsetOrderByFilesOwned(boolean skipIndexed) {
        /*
        Disregards deleted or replaced files when determining 'size' of dataset.
        Could possibly make more efficient by getting file metadata counts
        of latest published/draft version.
        Also disregards partitioning which is no longer supported.
        SEK - 11/09/2021
        */

        String skipClause = skipIndexed ? "AND o.indexTime is null " : "";
        Query query = em.createNativeQuery(" Select distinct(o.id), count(f.id) as numFiles FROM dvobject o " +
            "left join dvobject f on f.owner_id = o.id  where o.dtype = 'Dataset' "
                + skipClause
                + " group by o.id "
                + "ORDER BY count(f.id) asc, o.id");

        List<Object[]> queryResults;
        queryResults = query.getResultList();

        List<Long> retVal = new ArrayList();
        for (Object[] result : queryResults) {
            Long dsId;
            if (result[0] != null) {
                try {
                    dsId = Long.parseLong(result[0].toString()) ;
                } catch (Exception ex) {
                    dsId = null;
                }
                if (dsId == null) {
                    continue;
                }
                retVal.add(dsId);
            }
        }
        return retVal;
    }

    /**
     * Merges the passed dataset to the persistence context.
     * @param ds the dataset whose new state we want to persist.
     * @return The managed entity representing {@code ds}.
     */
    public Dataset merge( Dataset ds ) {
        return em.merge(ds);
    }

    public Dataset findByGlobalId(String globalId) {
        Dataset retVal = (Dataset) dvObjectService.findByGlobalId(globalId, "Dataset");
        if (retVal != null){
            return retVal;
        } else {
            //try to find with alternative PID
            return (Dataset) dvObjectService.findByGlobalId(globalId, "Dataset", true);
        }
    }

    /**
     * Instantiate dataset, and its components (DatasetVersions and FileMetadatas)
     * this method is used for object validation; if there are any invalid values
     * in the dataset components, a ConstraintViolationException will be thrown,
     * which can be further parsed to detect the specific offending values.
     * @param id the id of the dataset
     * @throws javax.validation.ConstraintViolationException
     */

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void instantiateDatasetInNewTransaction(Long id, boolean includeVariables) {
        Dataset dataset = find(id);
        for (DatasetVersion version : dataset.getVersions()) {
            for (FileMetadata fileMetadata : version.getFileMetadatas()) {
                // todo: make this optional!
                if (includeVariables) {
                    if (fileMetadata.getDataFile().isTabularData()) {
                        DataTable dataTable = fileMetadata.getDataFile().getDataTable();
                        for (DataVariable dataVariable : dataTable.getDataVariables()) {

                        }
                    }
                }
            }
        }
    }

    public String generateDatasetIdentifier(Dataset dataset, GlobalIdServiceBean idServiceBean) {
        String identifierType = settingsService.getValueForKey(SettingsServiceBean.Key.IdentifierGenerationStyle, "randomString");
        String shoulder = settingsService.getValueForKey(SettingsServiceBean.Key.Shoulder, "");

        switch (identifierType) {
            case "randomString":
                return generateIdentifierAsRandomString(dataset, idServiceBean, shoulder);
            case "storedProcGenerated":
                return generateIdentifierFromStoredProcedure(dataset, idServiceBean, shoulder);
            default:
                /* Should we throw an exception instead?? -- L.A. 4.6.2 */
                return generateIdentifierAsRandomString(dataset, idServiceBean, shoulder);
        }
    }

    private String generateIdentifierAsRandomString(Dataset dataset, GlobalIdServiceBean idServiceBean, String shoulder) {
        String identifier = null;
        do {
            identifier = shoulder + RandomStringUtils.randomAlphanumeric(6).toUpperCase();
        } while (!isIdentifierLocallyUnique(identifier, dataset));

        return identifier;
    }

    private String generateIdentifierFromStoredProcedure(Dataset dataset, GlobalIdServiceBean idServiceBean, String shoulder) {

        String identifier;
        do {
            StoredProcedureQuery query = this.em.createNamedStoredProcedureQuery("Dataset.generateIdentifierFromStoredProcedure");
            query.execute();
            String identifierFromStoredProcedure = (String) query.getOutputParameterValue(1);
            // some diagnostics here maybe - is it possible to determine that it's failing
            // because the stored procedure hasn't been created in the database?
            if (identifierFromStoredProcedure == null) {
                return null;
            }
            identifier = shoulder + identifierFromStoredProcedure;
        } while (!isIdentifierLocallyUnique(identifier, dataset));

        return identifier;
    }

    /**
     * Check that a identifier entered by the user is unique (not currently used
     * for any other study in this Dataverse Network) also check for duplicate
     * in EZID if needed
     * @param userIdentifier
     * @param dataset
     * @param persistentIdSvc
     * @return {@code true} if the identifier is unique, {@code false} otherwise.
     */
    public boolean isIdentifierUnique(String userIdentifier, Dataset dataset, GlobalIdServiceBean persistentIdSvc) {
        if ( ! isIdentifierLocallyUnique(userIdentifier, dataset) ) return false; // duplication found in local database

        // not in local DB, look in the persistent identifier service
        try {
            return ! persistentIdSvc.alreadyExists(dataset);
        } catch (Exception e){
            //we can live with failure - means identifier not found remotely
        }

        return true;
    }

    public boolean isIdentifierLocallyUnique(Dataset dataset) {
        return isIdentifierLocallyUnique(dataset.getIdentifier(), dataset);
    }

    public boolean isIdentifierLocallyUnique(String identifier, Dataset dataset) {
        return em.createNamedQuery("Dataset.findByIdentifierAuthorityProtocol")
            .setParameter("identifier", identifier)
            .setParameter("authority", dataset.getAuthority())
            .setParameter("protocol", dataset.getProtocol())
            .getResultList().isEmpty();
    }

    public Long getMaximumExistingDatafileIdentifier(Dataset dataset) {
        //Cannot rely on the largest table id having the greatest identifier counter
        long zeroFiles = new Long(0);
        Long retVal = zeroFiles;
        Long testVal;
        List<Object> idResults;
        Long dsId = dataset.getId();
        if (dsId != null) {
            try {
                idResults = em.createNamedQuery("Dataset.findIdentifierByOwnerId")
                                .setParameter("ownerId", dsId).getResultList();
            } catch (NoResultException ex) {
                logger.log(Level.FINE, "No files found in dataset id {0}. Returning a count of zero.", dsId);
                return zeroFiles;
            }
            if (idResults != null) {
                for (Object raw: idResults){
                    String identifier = (String) raw;
                    identifier =  identifier.substring(identifier.lastIndexOf("/") + 1);
                    testVal = new Long(identifier) ;
                    if (testVal > retVal){
                        retVal = testVal;
                    }
                }
            }
        }
        return retVal;
    }

    public DatasetVersion storeVersion( DatasetVersion dsv ) {
        em.persist(dsv);
        return dsv;
    }


    public DatasetVersionUser getDatasetVersionUser(DatasetVersion version, User user) {

        TypedQuery<DatasetVersionUser> query = em.createNamedQuery("DatasetVersionUser.findByVersionIdAndUserId", DatasetVersionUser.class);
        query.setParameter("versionId", version.getId());
        String identifier = user.getIdentifier();
        identifier = identifier.startsWith("@") ? identifier.substring(1) : identifier;
        AuthenticatedUser au = authentication.getAuthenticatedUser(identifier);
        query.setParameter("userId", au.getId());
        try {
            return query.getSingleResult();
        } catch (javax.persistence.NoResultException e) {
            return null;
        }
    }

    public boolean checkDatasetLock(Long datasetId) {
        TypedQuery<DatasetLock> lockCounter = em.createNamedQuery("DatasetLock.getLocksByDatasetId", DatasetLock.class);
        lockCounter.setParameter("datasetId", datasetId);
        lockCounter.setMaxResults(1);
        List<DatasetLock> lock = lockCounter.getResultList();
        return lock.size()>0;
    }

    public List<DatasetLock> getDatasetLocksByUser( AuthenticatedUser user) {

        return listLocks(null, user);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public DatasetLock addDatasetLock(Dataset dataset, DatasetLock lock) {
        lock.setDataset(dataset);
        dataset.addLock(lock);
        lock.setStartTime( new Date() );
        em.persist(lock);
        //em.merge(dataset);
        return lock;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW) /*?*/
    public DatasetLock addDatasetLock(Long datasetId, DatasetLock.Reason reason, Long userId, String info) {

        Dataset dataset = em.find(Dataset.class, datasetId);

        AuthenticatedUser user = null;
        if (userId != null) {
            user = em.find(AuthenticatedUser.class, userId);
        }

        // Check if the dataset is already locked for this reason:
        // (to prevent multiple, duplicate locks on the dataset!)
        DatasetLock lock = dataset.getLockFor(reason);
        if (lock != null) {
            return lock;
        }

        // Create new:
        lock = new DatasetLock(reason, user);
        lock.setDataset(dataset);
        lock.setInfo(info);
        lock.setStartTime(new Date());

        if (userId != null) {
            lock.setUser(user);
            if (user.getDatasetLocks() == null) {
                user.setDatasetLocks(new ArrayList<>());
            }
            user.getDatasetLocks().add(lock);
        }

        return addDatasetLock(dataset, lock);
    }

    /**
     * Removes all {@link DatasetLock}s for the dataset whose id is passed and reason
     * is {@code aReason}.
     * @param dataset the dataset whose locks (for {@code aReason}) will be removed.
     * @param aReason The reason of the locks that will be removed.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void removeDatasetLocks(Dataset dataset, DatasetLock.Reason aReason) {
        if ( dataset != null ) {
            new HashSet<>(dataset.getLocks()).stream()
                    .filter( l -> l.getReason() == aReason )
                    .forEach( lock -> {
                        lock = em.merge(lock);
                        dataset.removeLock(lock);

                        AuthenticatedUser user = lock.getUser();
                        user.getDatasetLocks().remove(lock);

                        em.remove(lock);
                    });
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void updateDatasetLock(DatasetLock datasetLock) {
        em.merge(datasetLock);
    }

    /*
     * Lists all dataset locks, optionally filtered by lock type or user, or both
     * @param lockType
     * @param user
     * @return a list of DatasetLocks
    */
    public List<DatasetLock> listLocks(DatasetLock.Reason lockType, AuthenticatedUser user) {

        TypedQuery<DatasetLock> query;

        if (lockType == null && user == null) {
            query = em.createNamedQuery("DatasetLock.findAll", DatasetLock.class);
        } else if (user == null) {
            query = em.createNamedQuery("DatasetLock.getLocksByType", DatasetLock.class);
            query.setParameter("lockType", lockType);
        } else if (lockType == null) {
            query = em.createNamedQuery("DatasetLock.getLocksByAuthenticatedUserId", DatasetLock.class);
            query.setParameter("authenticatedUserId", user.getId());
        } else {
            query = em.createNamedQuery("DatasetLock.getLocksByTypeAndAuthenticatedUserId", DatasetLock.class);
            query.setParameter("lockType", lockType);
            query.setParameter("authenticatedUserId", user.getId());
        }
        try {
            return query.getResultList();
        } catch (javax.persistence.NoResultException e) {
            return null;
        }
    }

    /*
    getTitleFromLatestVersion methods use native query to return a dataset title

        There are two versions:
     1) The version with datasetId param only will return the title regardless of version state
     2)The version with the param 'includeDraft' boolean  will return the most recently published title if the param is set to false
    If no Title found return empty string - protects against calling with
    include draft = false with no published version
    */

    public String getTitleFromLatestVersion(Long datasetId){
        return getTitleFromLatestVersion(datasetId, true);
    }

    public String getTitleFromLatestVersion(Long datasetId, boolean includeDraft){

        String whereDraft = "";
        //This clause will exclude draft versions from the select
        if (!includeDraft) {
            whereDraft = " and v.versionstate !='DRAFT' ";
        }

        try {
            return (String) em.createNativeQuery("select dfv.value  from dataset d "
                + " join datasetversion v on d.id = v.dataset_id "
                + " join datasetfield df on v.id = df.datasetversion_id "
                + " join datasetfieldvalue dfv on df.id = dfv.datasetfield_id "
                + " join datasetfieldtype dft on df.datasetfieldtype_id  = dft.id "
                + " where dft.name = '" + DatasetFieldConstant.title + "' and  v.dataset_id =" + datasetId
                + whereDraft
                + " order by v.versionnumber desc, v.minorVersionNumber desc limit 1 "
                + ";").getSingleResult();

        } catch (Exception ex) {
            logger.log(Level.INFO, "exception trying to get title from latest version: {0}", ex);
            return "";
        }

    }

    public Dataset getDatasetByHarvestInfo(Dataverse dataverse, String harvestIdentifier) {
        String queryStr = "SELECT d FROM Dataset d, DvObject o WHERE d.id = o.id AND o.owner.id = " + dataverse.getId() + " and d.harvestIdentifier = '" + harvestIdentifier + "'";
        Query query = em.createQuery(queryStr);
        List resultList = query.getResultList();
        Dataset dataset = null;
        if (resultList.size() > 1) {
            throw new EJBException("More than one dataset found in the dataverse (id= " + dataverse.getId() + "), with harvestIdentifier= " + harvestIdentifier);
        }
        if (resultList.size() == 1) {
            dataset = (Dataset) resultList.get(0);
        }
        return dataset;

    }

    public Long getDatasetVersionCardImage(Long versionId, User user) {
        if (versionId == null) {
            return null;
        }



        return null;
    }

    /**
     * Used to identify and properly display Harvested objects on the dataverse page.
     *
     * @param datasetIds
     * @return
     */
    public Map<Long, String> getArchiveDescriptionsForHarvestedDatasets(Set<Long> datasetIds){
        if (datasetIds == null || datasetIds.size() < 1) {
            return null;
        }

        String datasetIdStr = Strings.join(datasetIds, ", ");

        String qstr = "SELECT d.id, h.archiveDescription FROM harvestingClient h, dataset d WHERE d.harvestingClient_id = h.id AND d.id IN (" + datasetIdStr + ")";
        List<Object[]> searchResults;

        try {
            searchResults = em.createNativeQuery(qstr).getResultList();
        } catch (Exception ex) {
            searchResults = null;
        }

        if (searchResults == null) {
            return null;
        }

        Map<Long, String> ret = new HashMap<>();

        for (Object[] result : searchResults) {
            Long dsId;
            if (result[0] != null) {
                try {
                    dsId = (Long)result[0];
                } catch (Exception ex) {
                    dsId = null;
                }
                if (dsId == null) {
                    continue;
                }

                ret.put(dsId, (String)result[1]);
            }
        }

        return ret;
    }



    public boolean isDatasetCardImageAvailable(DatasetVersion datasetVersion, User user) {
        if (datasetVersion == null) {
            return false;
        }

        // First, check if this dataset has a designated thumbnail image:

        if (datasetVersion.getDataset() != null) {
            DataFile dataFile = datasetVersion.getDataset().getThumbnailFile();
            if (dataFile != null) {
                return ImageThumbConverter.isThumbnailAvailable(dataFile, 48);
            }
        }

        // If not, we'll try to use one of the files in this dataset version:
        // (the first file with an available thumbnail, really)

        List<FileMetadata> fileMetadatas = datasetVersion.getFileMetadatas();

        for (FileMetadata fileMetadata : fileMetadatas) {
            DataFile dataFile = fileMetadata.getDataFile();

            // TODO: use permissionsWrapper here - ?
            // (we are looking up these download permissions on individual files,
            // true, and those are unique... but the wrapper may be able to save
            // us some queries when it determines the download permission on the
            // dataset as a whole? -- L.A. 4.2.1

            if (fileService.isThumbnailAvailable(dataFile) && permissionService.userOn(user, dataFile).has(Permission.DownloadFile)) { //, user)) {
                return true;
            }

        }

        return false;
    }


    // reExportAll *forces* a reexport on all published datasets; whether they
    // have the "last export" time stamp set or not.
    @Asynchronous
    public void reExportAllAsync() {
        exportAllDatasets(true);
    }

    public void reExportAll() {
        exportAllDatasets(true);
    }


    // exportAll() will try to export the yet unexported datasets (it will honor
    // and trust the "last export" time stamp).

    @Asynchronous
    public void exportAllAsync() {
        exportAllDatasets(false);
    }

    public void exportAll() {
        exportAllDatasets(false);
    }

    public void exportAllDatasets(boolean forceReExport) {
        Integer countAll = 0;
        Integer countSuccess = 0;
        Integer countError = 0;
        String logTimestamp = logFormatter.format(new Date());
        Logger exportLogger = Logger.getLogger("edu.harvard.iq.dataverse.harvest.client.DatasetServiceBean." + "ExportAll" + logTimestamp);
        String logFileName = "../logs" + File.separator + "export_" + logTimestamp + ".log";
        FileHandler fileHandler;
        boolean fileHandlerSuceeded;
        try {
            fileHandler = new FileHandler(logFileName);
            exportLogger.setUseParentHandlers(false);
            fileHandlerSuceeded = true;
        } catch (IOException | SecurityException ex) {
            Logger.getLogger(DatasetServiceBean.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }

        if (fileHandlerSuceeded) {
            exportLogger.addHandler(fileHandler);
        } else {
            exportLogger = logger;
        }

        exportLogger.info("Starting an export all job");

        for (Long datasetId : findAllLocalDatasetIds()) {
            // Potentially, there's a godzillion datasets in this Dataverse.
            // This is why we go through the list of ids here, and instantiate
            // only one dataset at a time.
            Dataset dataset = this.find(datasetId);
            if (dataset != null) {
                // Accurate "is published?" test - ?
                // Answer: Yes, it is! We can't trust dataset.isReleased() alone; because it is a dvobject method
                // that returns (publicationDate != null). And "publicationDate" is essentially
                // "the first publication date"; that stays the same as versions get
                // published and/or deaccessioned. But in combination with !isDeaccessioned()
                // it is indeed an accurate test.
                if (dataset.isReleased() && dataset.getReleasedVersion() != null && !dataset.isDeaccessioned()) {

                    // can't trust dataset.getPublicationDate(), no.
                    Date publicationDate = dataset.getReleasedVersion().getReleaseTime(); // we know this dataset has a non-null released version! Maybe not - SEK 8/19 (We do now! :)
                    if (forceReExport || (publicationDate != null
                            && (dataset.getLastExportTime() == null
                            || dataset.getLastExportTime().before(publicationDate)))) {
                        countAll++;
                        try {
                            recordService.exportAllFormatsInNewTransaction(dataset);
                            exportLogger.info("Success exporting dataset: " + dataset.getDisplayName() + " " + dataset.getGlobalIdString());
                            countSuccess++;
                        } catch (Exception ex) {
                            exportLogger.info("Error exporting dataset: " + dataset.getDisplayName() + " " + dataset.getGlobalIdString() + "; " + ex.getMessage());
                            countError++;
                        }
                    }
                }
            }
        }
        exportLogger.info("Datasets processed: " + countAll.toString());
        exportLogger.info("Datasets exported successfully: " + countSuccess.toString());
        exportLogger.info("Datasets failures: " + countError.toString());
        exportLogger.info("Finished export-all job.");

        if (fileHandlerSuceeded) {
            fileHandler.close();
        }

    }
    
    public String getReminderString(Dataset dataset, boolean canPublishDataset) {
        return getReminderString( dataset, canPublishDataset, false);
    }

    //get a string to add to save success message
    //depends on page (dataset/file) and user privleges
    public String getReminderString(Dataset dataset, boolean canPublishDataset, boolean filePage) {
       
        String reminderString;

        if (canPublishDataset) {
            reminderString = BundleUtil.getStringFromBundle("dataset.message.publish.warning");
        } else {
            reminderString = BundleUtil.getStringFromBundle("dataset.message.submit.warning");
        }

        if (canPublishDataset) {
            if (!filePage) {
                reminderString = reminderString + " " + BundleUtil.getStringFromBundle("dataset.message.publish.remind.draft");
            } else {
                reminderString = reminderString + " " + BundleUtil.getStringFromBundle("dataset.message.publish.remind.draft.filePage");
                reminderString = reminderString.replace("{0}", "" + (dataset.getGlobalId().asString().concat("&version=DRAFT")));
            }
        } else {
            if (!filePage) {
                reminderString = reminderString + " " + BundleUtil.getStringFromBundle("dataset.message.submit.remind.draft");
            } else {
                reminderString = reminderString + " " + BundleUtil.getStringFromBundle("dataset.message.submit.remind.draft.filePage");
                reminderString = reminderString.replace("{0}", "" + (dataset.getGlobalId().asString().concat("&version=DRAFT")));
            }
        }

        if (reminderString != null) {
            return reminderString;
        } else {
            logger.warning("Unable to get reminder string from bundle. Returning empty string.");
            return "";
        }
    }

    public Dataset setNonDatasetFileAsThumbnail(Dataset dataset, InputStream inputStream) {
        if (dataset == null) {
            logger.fine("In setNonDatasetFileAsThumbnail but dataset is null! Returning null.");
            return null;
        }
        if (inputStream == null) {
            logger.fine("In setNonDatasetFileAsThumbnail but inputStream is null! Returning null.");
            return null;
        }
        dataset = DatasetUtil.persistDatasetLogoToStorageAndCreateThumbnails(dataset, inputStream);
        dataset.setThumbnailFile(null);
        return merge(dataset);
    }

    public Dataset setDatasetFileAsThumbnail(Dataset dataset, DataFile datasetFileThumbnailToSwitchTo) {
        if (dataset == null) {
            logger.fine("In setDatasetFileAsThumbnail but dataset is null! Returning null.");
            return null;
        }
        if (datasetFileThumbnailToSwitchTo == null) {
            logger.fine("In setDatasetFileAsThumbnail but dataset is null! Returning null.");
            return null;
        }
        DatasetUtil.deleteDatasetLogo(dataset);
        dataset.setThumbnailFile(datasetFileThumbnailToSwitchTo);
        dataset.setUseGenericThumbnail(false);
        return merge(dataset);
    }

    public Dataset removeDatasetThumbnail(Dataset dataset) {
        if (dataset == null) {
            logger.fine("In removeDatasetThumbnail but dataset is null! Returning null.");
            return null;
        }
        DatasetUtil.deleteDatasetLogo(dataset);
        dataset.setThumbnailFile(null);
        dataset.setUseGenericThumbnail(true);
        return merge(dataset);
    }

    // persist assigned thumbnail in a single one-field-update query:
    // (the point is to avoid doing an em.merge() on an entire dataset object...)
    public void assignDatasetThumbnailByNativeQuery(Long datasetId, Long dataFileId) {
        try {
            em.createNativeQuery("UPDATE dataset SET thumbnailfile_id=" + dataFileId + " WHERE id=" + datasetId).executeUpdate();
        } catch (Exception ex) {
            // it's ok to just ignore...
        }
    }

    public void assignDatasetThumbnailByNativeQuery(Dataset dataset, DataFile dataFile) {
        try {
            em.createNativeQuery("UPDATE dataset SET thumbnailfile_id=" + dataFile.getId() + " WHERE id=" + dataset.getId()).executeUpdate();
        } catch (Exception ex) {
            // it's ok to just ignore...
        }
    }

    public WorkflowComment addWorkflowComment(WorkflowComment workflowComment) {
        em.persist(workflowComment);
        return workflowComment;
    }

    public void markWorkflowCommentAsRead(WorkflowComment workflowComment) {
        workflowComment.setToBeShown(false);
        em.merge(workflowComment);
    }


    /**
     * This method used to throw CommandException, which was pretty pointless
     * seeing how it's called asynchronously. As of v5.0 any CommanExceptiom
     * thrown by the FinalizeDatasetPublicationCommand below will be caught
     * and we'll log it as a warning - which is the best we can do at this point.
     * Any failure notifications to users should be sent from inside the command.
     */
    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void callFinalizePublishCommandAsynchronously(Long datasetId, CommandContext ctxt, DataverseRequest request, boolean isPidPrePublished) {

        // Since we are calling the next command asynchronously anyway - sleep here
        // for a few seconds, just in case, to make sure the database update of
        // the dataset initiated by the PublishDatasetCommand has finished,
        // to avoid any concurrency/optimistic lock issues.
        // Aug. 2020/v5.0: It MAY be working consistently without any
        // sleep here, after the call the method has been moved to the onSuccess()
        // portion of the PublishDatasetCommand. I'm going to leave the 1 second
        // sleep below, for just in case reasons: -- L.A.
        try {
            Thread.sleep(1000);
        } catch (Exception ex) {
            logger.warning("Failed to sleep for a second.");
        }
        logger.fine("Running FinalizeDatasetPublicationCommand, asynchronously");
        Dataset theDataset = find(datasetId);
        try {
            commandEngine.submit(new FinalizeDatasetPublicationCommand(theDataset, request, isPidPrePublished));
        } catch (CommandException cex) {
            logger.warning("CommandException caught when executing the asynchronous portion of the Dataset Publication Command.");
        }
    }

    /*
     Experimental asynchronous method for requesting persistent identifiers for
     datafiles. We decided not to run this method on upload/create (so files
     will not have persistent ids while in draft; when the draft is published,
     we will force obtaining persistent ids for all the files in the version.

     If we go back to trying to register global ids on create, care will need to
     be taken to make sure the asynchronous changes below are not conflicting with
     the changes from file ingest (which may be happening in parallel, also
     asynchronously). We would also need to lock the dataset (similarly to how
     tabular ingest logs the dataset), to prevent the user from publishing the
     version before all the identifiers get assigned - otherwise more conflicts
     are likely. (It sounds like it would make sense to treat these two tasks -
     persistent identifiers for files and ingest - as one post-upload job, so that
     they can be run in sequence). -- L.A. Mar. 2018
    */
    @Asynchronous
    public void obtainPersistentIdentifiersForDatafiles(Dataset dataset) {
        GlobalIdServiceBean idServiceBean = GlobalIdServiceBean.getBean(dataset.getProtocol(), commandEngine.getContext());

        //If the Id type is sequential and Dependent then write file idenitifiers outside the command
        String datasetIdentifier = dataset.getIdentifier();
        Long maxIdentifier = null;

        if (systemConfig.isDataFilePIDSequentialDependent()) {
            maxIdentifier = getMaximumExistingDatafileIdentifier(dataset);
        }

        for (DataFile datafile : dataset.getFiles()) {
            logger.info("Obtaining persistent id for datafile id=" + datafile.getId());

            if (datafile.getIdentifier() == null || datafile.getIdentifier().isEmpty()) {

                logger.info("Obtaining persistent id for datafile id=" + datafile.getId());

                if (maxIdentifier != null) {
                    maxIdentifier++;
                    datafile.setIdentifier(datasetIdentifier + "/" + maxIdentifier.toString());
                } else {
                    datafile.setIdentifier(fileService.generateDataFileIdentifier(datafile, idServiceBean));
                }

                if (datafile.getProtocol() == null) {
                    datafile.setProtocol(settingsService.getValueForKey(SettingsServiceBean.Key.Protocol, ""));
                }
                if (datafile.getAuthority() == null) {
                    datafile.setAuthority(settingsService.getValueForKey(SettingsServiceBean.Key.Authority, ""));
                }

                logger.info("identifier: " + datafile.getIdentifier());

                String doiRetString;

                try {
                    logger.log(Level.FINE, "creating identifier");
                    doiRetString = idServiceBean.createIdentifier(datafile);
                } catch (Throwable e) {
                    logger.log(Level.WARNING, "Exception while creating Identifier: " + e.getMessage(), e);
                    doiRetString = "";
                }

                // Check return value to make sure registration succeeded
                if (!idServiceBean.registerWhenPublished() && doiRetString.contains(datafile.getIdentifier())) {
                    datafile.setIdentifierRegistered(true);
                    datafile.setGlobalIdCreateTime(new Date());
                }

                DataFile merged = em.merge(datafile);
                merged = null;
            }

        }
    }

    public long findStorageSize(Dataset dataset) throws IOException {
        return findStorageSize(dataset, false, GetDatasetStorageSizeCommand.Mode.STORAGE, null);
    }


    public long findStorageSize(Dataset dataset, boolean countCachedExtras) throws IOException {
        return findStorageSize(dataset, countCachedExtras, GetDatasetStorageSizeCommand.Mode.STORAGE, null);
    }

    /**
     * Returns the total byte size of the files in this dataset
     *
     * @param dataset
     * @param countCachedExtras boolean indicating if the cached disposable extras should also be counted
     * @param mode String indicating whether we are getting the result for storage (entire dataset) or download version based
     * @param version optional param for dataset version
     * @return total size
     * @throws IOException if it can't access the objects via StorageIO
     * (in practice, this can only happen when called with countCachedExtras=true; when run in the
     * default mode, the method doesn't need to access the storage system, as the
     * sizes of the main files are recorded in the database)
     */
    public long findStorageSize(Dataset dataset, boolean countCachedExtras, GetDatasetStorageSizeCommand.Mode mode, DatasetVersion version) throws IOException {
        long total = 0L;

        if (dataset.isHarvested()) {
            return 0L;
        }

        List<DataFile> filesToTally = new ArrayList();

        if (version == null || (mode != null &&  mode.equals("storage"))){
            filesToTally = dataset.getFiles();
        } else {
            List <FileMetadata>  fmds = version.getFileMetadatas();
            for (FileMetadata fmd : fmds){
                    filesToTally.add(fmd.getDataFile());
            }
        }


        //CACHED EXTRAS FOR DOWNLOAD?


        for (DataFile datafile : filesToTally) {
                total += datafile.getFilesize();

                if (!countCachedExtras) {
                    if (datafile.isTabularData()) {
                        // count the size of the stored original, in addition to the main tab-delimited file:
                        Long originalFileSize = datafile.getDataTable().getOriginalFileSize();
                        if (originalFileSize != null) {
                            total += originalFileSize;
                        }
                    }
                } else {
                    StorageIO<DataFile> storageIO = datafile.getStorageIO();
                    for (String cachedFileTag : storageIO.listAuxObjects()) {
                        total += storageIO.getAuxObjectSize(cachedFileTag);
                    }
                }
            }

        // and finally,
        if (countCachedExtras) {
            // count the sizes of the files cached for the dataset itself
            // (i.e., the metadata exports):
            StorageIO<Dataset> datasetSIO = DataAccess.getStorageIO(dataset);

            for (String[] exportProvider : ExportService.getInstance().getExportersLabels()) {
                String exportLabel = "export_" + exportProvider[1] + ".cached";
                try {
                    total += datasetSIO.getAuxObjectSize(exportLabel);
                } catch (IOException ioex) {
                    // safe to ignore; object not cached
                }
            }
        }

        return total;
    }

    /**
     * An optimized method for deleting a harvested dataset.
     *
     * @param dataset
     * @param request DataverseRequest (for initializing the DestroyDatasetCommand)
     * @param hdLogger logger object (in practice, this will be a separate log file created for a specific harvesting job)
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void deleteHarvestedDataset(Dataset dataset, DataverseRequest request, Logger hdLogger) {
        // Purge all the SOLR documents associated with this client from the
        // index server:
        indexService.deleteHarvestedDocuments(dataset);

        try {
            // files from harvested datasets are removed unceremoniously,
            // directly in the database. no need to bother calling the
            // DeleteFileCommand on them.
            for (DataFile harvestedFile : dataset.getFiles()) {
                DataFile merged = em.merge(harvestedFile);
                em.remove(merged);
                harvestedFile = null;
            }
            dataset.setFiles(null);
            Dataset merged = em.merge(dataset);
            commandEngine.submit(new DestroyDatasetCommand(merged, request));
            hdLogger.info("Successfully destroyed the dataset");
        } catch (Exception ex) {
            hdLogger.warning("Failed to destroy the dataset");
        }
    }
}
