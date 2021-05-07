/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.orcid.consumer;

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.dspace.app.orcid.OrcidHistory;
import org.dspace.app.orcid.OrcidOperation;
import org.dspace.app.orcid.OrcidQueue;
import org.dspace.app.orcid.factory.OrcidServiceFactory;
import org.dspace.app.orcid.model.OrcidEntityType;
import org.dspace.app.orcid.model.factory.OrcidProfileSectionFactory;
import org.dspace.app.orcid.service.OrcidHistoryService;
import org.dspace.app.orcid.service.OrcidProfileSectionFactoryService;
import org.dspace.app.orcid.service.OrcidQueueService;
import org.dspace.app.orcid.service.OrcidSynchronizationService;
import org.dspace.app.profile.OrcidProfileSyncPreference;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataFieldName;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.core.CrisConstants;
import org.dspace.event.Consumer;
import org.dspace.event.Event;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.util.UUIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The consumer to fill the ORCID queue.
 *
 * @author Luca Giamminonni (luca.giamminonni at 4science.it)
 *
 */
public class OrcidQueueConsumer implements Consumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrcidQueueConsumer.class);

    private OrcidQueueService orcidQueueService;

    private OrcidHistoryService orcidHistoryService;

    private OrcidSynchronizationService orcidSynchronizationService;

    private ItemService itemService;

    private OrcidProfileSectionFactoryService profileSectionFactoryService;

    private ConfigurationService configurationService;

    private List<UUID> alreadyConsumedItems = new ArrayList<>();

    @Override
    public void initialize() throws Exception {

        OrcidServiceFactory orcidServiceFactory = OrcidServiceFactory.getInstance();

        this.orcidQueueService = orcidServiceFactory.getOrcidQueueService();
        this.orcidHistoryService = orcidServiceFactory.getOrcidHistoryService();
        this.orcidSynchronizationService = orcidServiceFactory.getOrcidSynchronizationService();
        this.profileSectionFactoryService = orcidServiceFactory.getOrcidProfileSectionFactoryService();
        this.configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

        this.itemService = ContentServiceFactory.getInstance().getItemService();
    }

    @Override
    public void consume(Context context, Event event) throws Exception {
        DSpaceObject dso = event.getSubject(context);
        if (!(dso instanceof Item)) {
            return;
        }
        Item item = (Item) dso;
        if (!item.isArchived()) {
            return;
        }

        if (alreadyConsumedItems.contains(item.getID())) {
            return;
        }

        context.turnOffAuthorisationSystem();
        try {
            consumeItem(context, item);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private void consumeItem(Context context, Item item) throws SQLException {

        String entityType = itemService.getEntityType(item);
        if (entityType == null) {
            return;
        }

        if (OrcidEntityType.isValid(entityType)) {
            consumeEntity(context, item);
        } else if (entityType.equals(getProfileType())) {
            consumeProfile(context, item);
        }

        alreadyConsumedItems.add(item.getID());

    }

    private void consumeEntity(Context context, Item entity) throws SQLException {
        List<MetadataValue> metadataValues = entity.getMetadata();

        for (MetadataValue metadata : metadataValues) {

            String authority = metadata.getAuthority();

            if (isNestedMetadataPlaceholder(metadata)) {
                continue;
            }

            UUID relatedItemUuid = UUIDUtils.fromString(authority);
            if (relatedItemUuid == null) {
                continue;
            }

            Item owner = itemService.find(context, relatedItemUuid);

            if (isNotProfileItem(owner) || isNotLinkedToOrcid(owner)) {
                continue;
            }

            if (shouldNotBeSynchronized(owner, entity) || isAlreadyQueued(context, owner, entity)) {
                continue;
            }

            createOrcidQueue(context, owner, entity);

        }

    }

    private void consumeProfile(Context context, Item item) throws SQLException {

        if (isNotLinkedToOrcid(item) || profileShouldNotBeSynchronized(item)) {
            return;
        }

        for (OrcidProfileSectionFactory factory : findProfileFactories(item)) {
            consumeProfile(context, item, factory);
        }

    }

    private void consumeProfile(Context context, Item item, OrcidProfileSectionFactory factory) throws SQLException {

        String sectionType = factory.getProfileSectionType().name();
        List<String> signatures = factory.getMetadataSignatures(context, item);

        List<OrcidHistory> historyRecords = findOrcidHistoryRecords(context, item, sectionType);

        deleteRecordsByEntityAndType(context, item, sectionType);
        createInsertionRecordForNewSignatures(context, item, historyRecords, factory, signatures);
        createDeletionRecordForNoMorePresentSignatures(context, item, historyRecords, factory, signatures);

    }

    private void deleteRecordsByEntityAndType(Context context, Item item, String sectionType) throws SQLException {
        orcidQueueService.deleteByEntityAndRecordType(context, item, sectionType);
    }

    private void createInsertionRecordForNewSignatures(Context context, Item item, List<OrcidHistory> historyRecords,
        OrcidProfileSectionFactory factory, List<String> signatures) throws SQLException {

        String sectionType = factory.getProfileSectionType().name();

        for (String signature : signatures) {

            if (isNotAlreadySynchronized(historyRecords, signature)) {
                String description = factory.getDescription(context, item, signature);
                orcidQueueService.createProfileInsertionRecord(context, item, description, sectionType, signature);
            }

        }

    }

    private void createDeletionRecordForNoMorePresentSignatures(Context context, Item profile,
        List<OrcidHistory> historyRecords, OrcidProfileSectionFactory factory, List<String> signatures)
        throws SQLException {

        String sectionType = factory.getProfileSectionType().name();

        List<String> deletedSignatures = historyRecords.stream()
            .filter(historyRecord -> historyRecord.getOperation() == OrcidOperation.DELETE)
            .map(OrcidHistory::getMetadata)
            .collect(Collectors.toList());

        for (OrcidHistory historyRecord : historyRecords) {
            String storedSignature = historyRecord.getMetadata();
            String putCode = historyRecord.getPutCode();
            String description = historyRecord.getDescription();

            if (signatures.contains(storedSignature) || deletedSignatures.contains(storedSignature)) {
                continue;
            }

            if (StringUtils.isBlank(putCode)) {
                LOGGER.warn("The orcid history record with id {} should have a not blank put code");
                continue;
            }

            orcidQueueService.createProfileDeletionRecord(context, profile, description,
                sectionType, storedSignature, putCode);
        }

    }

    private List<OrcidHistory> findOrcidHistoryRecords(Context context, Item item,
        String sectionType) throws SQLException {
        return orcidHistoryService.findSuccessfullyRecordsByEntityAndType(context, item, sectionType);
    }

    private boolean isNotAlreadySynchronized(List<OrcidHistory> records, String signature) {
        return getLastOperation(records, signature)
            .map(operation -> operation == OrcidOperation.DELETE)
            .orElse(Boolean.TRUE);
    }

    private Optional<OrcidOperation> getLastOperation(List<OrcidHistory> records, String signature) {
        return records.stream()
            .filter(record -> signature.equals(record.getMetadata()))
            .sorted(comparing(OrcidHistory::getTimestamp, nullsFirst(naturalOrder())).reversed())
            .map(OrcidHistory::getOperation)
            .findFirst();
    }

    private boolean isAlreadyQueued(Context context, Item owner, Item entity) throws SQLException {
        return isNotEmpty(orcidQueueService.findByOwnerAndEntity(context, owner, entity));
    }

    private boolean isNotLinkedToOrcid(Item ownerItem) {
        return getMetadataValue(ownerItem, "cris.orcid.access-token") == null
            || getMetadataValue(ownerItem, "person.identifier.orcid") == null;
    }

    private boolean shouldNotBeSynchronized(Item owner, Item entity) {
        return !orcidSynchronizationService.isSynchronizationEnabled(owner, entity);
    }

    private boolean profileShouldNotBeSynchronized(Item item) {
        return !orcidSynchronizationService.isSynchronizationEnabled(item, item);
    }

    private boolean isNotProfileItem(Item ownerItem) {
        return !getProfileType().equals(itemService.getEntityType(ownerItem));
    }

    private boolean isNestedMetadataPlaceholder(MetadataValue metadata) {
        return StringUtils.equals(metadata.getValue(), CrisConstants.PLACEHOLDER_PARENT_METADATA_VALUE);
    }

    private OrcidQueue createOrcidQueue(Context context, Item owner, Item entity) throws SQLException {
        Optional<String> putCode = orcidHistoryService.findLastPutCode(context, owner, entity);
        if (putCode.isPresent()) {
            return orcidQueueService.createEntityUpdateRecord(context, owner, entity, putCode.get());
        } else {
            return orcidQueueService.createEntityInsertionRecord(context, owner, entity);
        }
    }

    private String getMetadataValue(Item item, String metadataField) {
        return itemService.getMetadataFirstValue(item, new MetadataFieldName(metadataField), Item.ANY);
    }

    private List<OrcidProfileSectionFactory> findProfileFactories(Item item) {
        List<OrcidProfileSyncPreference> profilePreferences = orcidSynchronizationService.getProfilePreferences(item);
        return this.profileSectionFactoryService.findByPreferences(profilePreferences);
    }

    private String getProfileType() {
        return configurationService.getProperty("researcher-profile.type", "Person");
    }

    @Override
    public void end(Context context) throws Exception {
        alreadyConsumedItems.clear();
    }

    @Override
    public void finish(Context context) throws Exception {
        // nothing to do
    }

}