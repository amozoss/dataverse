package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.*;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author skraffmiller
 */
@RequiredPermissions(Permission.EditDataset)
public class UpdateDatasetCommand extends AbstractDatasetCommand<Dataset> {

    private static final Logger logger = Logger.getLogger(UpdateDatasetCommand.class.getCanonicalName());
    private final List<FileMetadata> filesToDelete;
    private boolean validateLenient = false;
    private static final int FOOLPROOF_RETRIAL_ATTEMPTS_LIMIT = 2 ^ 8;
    
    public UpdateDatasetCommand(Dataset theDataset, DataverseRequest aRequest) {
        super(aRequest, theDataset);
        this.filesToDelete = new ArrayList<>();
    }    
    
    public UpdateDatasetCommand(Dataset theDataset, DataverseRequest aRequest, List<FileMetadata> filesToDelete) {
        super(aRequest, theDataset);
        this.filesToDelete = filesToDelete;
    }
    
    public UpdateDatasetCommand(Dataset theDataset, DataverseRequest aRequest, DataFile fileToDelete) {
        super(aRequest, theDataset);
        
        // get the latest file metadata for the file; ensuring that it is a draft version
        this.filesToDelete = new ArrayList<>();
        for (FileMetadata fmd : theDataset.getEditVersion().getFileMetadatas()) {
            if (fmd.getDataFile().equals(fileToDelete)) {
                filesToDelete.add(fmd);
                break;
            }
        }
    }    

    public boolean isValidateLenient() {
        return validateLenient;
    }

    public void setValidateLenient(boolean validateLenient) {
        this.validateLenient = validateLenient;
    }

    @Override
    public Dataset execute(CommandContext ctxt) throws CommandException {
        if ( ! (getUser() instanceof AuthenticatedUser) ) {
            throw new IllegalCommandException("Only authenticated users can update datasets", this);
        }
        
        ctxt.permissions().checkEditDatasetLock(getDataset(), getRequest(), this);
        // Dataset has no locks prventing the update
        
        getDataset().getEditVersion().setDatasetFields(getDataset().getEditVersion().initDatasetFields());
        validateOrDie( getDataset().getEditVersion(), isValidateLenient() );
        
        return save(ctxt);
    }

    public Dataset save(CommandContext ctxt)  throws CommandException {
        final DatasetVersion editVersion = getDataset().getEditVersion();
        tidyUpFields(editVersion);
        
        if ( editVersion.getCreateTime() == null ) {
            editVersion.setCreateTime(getTimestamp());
        }
        
        for (DataFile dataFile : getDataset().getFiles()) {
            if (dataFile.getCreateDate() == null) {
                dataFile.setCreateDate(getTimestamp());
                dataFile.setCreator((AuthenticatedUser) getUser());
            }
            dataFile.setModificationTime(getTimestamp());
        }
                
        // Remove / delete any files that were removed
        
        // If any of the files that we are deleting has a UNF, we will need to 
        // re-calculate the UNF of the version - since that is the product 
        // of the UNFs of the individual files. 
        boolean recalculateUNF = false;
        /* The separate loop is just to make sure that the dataset database is 
        updated, specifically when an image datafile is being deleted, which
        is being used as the dataset thumbnail as part of a batch delete. 
        if we don't remove the thumbnail association with the dataset before the 
        actual deletion of the file, it might throw foreign key integration 
        violation exceptions. 
        */
        for (FileMetadata fmd : filesToDelete){
             //  check if this file is being used as the default thumbnail
            if (fmd.getDataFile().equals(getDataset().getThumbnailFile())) {
                logger.fine("deleting the dataset thumbnail designation");
                getDataset().setThumbnailFile(null);
            }
            
            if (fmd.getDataFile().getUnf() != null) {
                recalculateUNF = true;
            }
        }
        // we have to merge to update the database but not flush because 
        // we don't want to create two draft versions!
        Dataset tempDataset = ctxt.em().merge(getDataset());
        
        for (FileMetadata fmd : filesToDelete) {
            if (!fmd.getDataFile().isReleased()) {
                // if file is draft (ie. new to this version, delete; otherwise just remove filemetadata object)
                ctxt.engine().submit(new DeleteDataFileCommand(fmd.getDataFile(), getRequest()));
                tempDataset.getFiles().remove(fmd.getDataFile());
                tempDataset.getEditVersion().getFileMetadatas().remove(fmd);
                // added this check to handle issue where you could not deleter a file that shared a category with a new file
                // the relation ship does not seem to cascade, yet somehow it was trying to merge the filemetadata
                // todo: clean this up some when we clean the create / update dataset methods
                for (DataFileCategory cat : tempDataset.getCategories()) {
                    cat.getFileMetadatas().remove(fmd);
                }
            } else {
                FileMetadata mergedFmd = ctxt.em().merge(fmd);
                ctxt.em().remove(mergedFmd);
                fmd.getDataFile().getFileMetadatas().remove(fmd);
                tempDataset.getEditVersion().getFileMetadatas().remove(fmd);
            }      
        }        
        
        if (recalculateUNF) {
            ctxt.ingest().recalculateDatasetVersionUNF(tempDataset.getEditVersion());
        }
        
        String nonNullDefaultIfKeyNotFound = "";
        String pidProvider = ctxt.settings().getValueForKey(SettingsServiceBean.Key.DoiProvider, nonNullDefaultIfKeyNotFound);
        
        PersistentIdentifierServiceBean idServiceBean = PersistentIdentifierServiceBean.getBean(ctxt);
        boolean registerWhenPublished = idServiceBean.registerWhenPublished();
        logger.log(Level.FINE,"doiProvider={0} protocol={1} GlobalIdCreateTime=={2}", new Object[]{pidProvider, tempDataset.getProtocol(), tempDataset.getGlobalIdCreateTime()});
        if ( !registerWhenPublished && tempDataset.getGlobalIdCreateTime() == null) {
            try {
                logger.fine("creating identifier");
               
                String doiRetString = idServiceBean.createIdentifier(tempDataset);
                int attempts = 0;
                while (!doiRetString.contains(tempDataset.getIdentifier())
                       && doiRetString.contains("identifier already exists")
                       && attempts < FOOLPROOF_RETRIAL_ATTEMPTS_LIMIT) {
                    // if the identifier exists, we'll generate another one
                    // and try to register again... but only up to some
                    // reasonably high number of times - so that we don't 
                    // go into an infinite loop here, if PID service is giving us 
                    // these duplicate messages in error. 
                    // 
                    // (and we do want the limit to be a "reasonably high" number! 
                    // true, if our identifiers are randomly generated strings, 
                    // then it is highly unlikely that we'll ever run into a 
                    // duplicate race condition repeatedly; but if they are sequential
                    // numeric values, than it is entirely possible that a large
                    // enough number of values will be legitimately registered 
                    // by another entity sharing the same authority...)
                
                    tempDataset.setIdentifier(ctxt.datasets().generateDatasetIdentifier(tempDataset, idServiceBean));
                    doiRetString = idServiceBean.createIdentifier(tempDataset);

                    attempts++;
                }
                // And if the registration failed for some reason other that an 
                // existing duplicate identifier - for example, EZID down --
                // we simply give up. 
                if (doiRetString.contains(tempDataset.getIdentifier())) {
                    tempDataset.setGlobalIdCreateTime(new Timestamp(new Date().getTime()));
                    
                } else if (doiRetString.contains("identifier already exists")) {
                    logger.log(Level.WARNING, "EZID refused registration, requested id(s) already in use; gave up after {0} attempts. Current (last requested) identifier: {1}", new Object[]{attempts, tempDataset.getIdentifier()});
                    
                } else {
                    logger.log(Level.WARNING, "Failed to create identifier ({0}) with {2}: {1}", 
                        new Object[]{tempDataset.getIdentifier(),
                            doiRetString, idServiceBean.getProviderInformation().toString()
                        });
                    
                }
                   
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Failed to create identifier: " + e.getMessage(), e);
                // Remote PID service probably down
            }
        }
        
        
        tempDataset.getEditVersion().setLastUpdateTime(getTimestamp());
        tempDataset.setModificationTime(getTimestamp());
         
        Dataset savedDataset = ctxt.em().merge(tempDataset);
        ctxt.em().flush();

        updateDatasetUser(ctxt);
        ctxt.index().indexDataset(savedDataset, true);

        return savedDataset;
    }

}
