package at.roteskreuz.stopcorona.model.manager

import at.roteskreuz.stopcorona.model.db.dao.InfectionMessageDao
import at.roteskreuz.stopcorona.model.db.dao.NearbyRecordDao
import at.roteskreuz.stopcorona.model.entities.infection.message.MessageType
import at.roteskreuz.stopcorona.model.repositories.ConfigurationRepository
import at.roteskreuz.stopcorona.model.repositories.CoronaDetectionRepository
import at.roteskreuz.stopcorona.model.repositories.QuarantineRepository
import at.roteskreuz.stopcorona.model.repositories.QuarantineStatus
import at.roteskreuz.stopcorona.skeleton.core.model.helpers.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.threeten.bp.ZonedDateTime
import kotlin.coroutines.CoroutineContext

/**
 * Manages the cleanup of old contact and messages entries in the database.
 */
interface DatabaseCleanupManager {

    /**
     * Remove incoming green messages immediately.
     */
    suspend fun removeReceivedGreenMessages()

    /**
     * Removes all outgoing yellow messages immediately when user revokes probably sick status.
     */
    suspend fun removeSentYellowMessages()
}

class DatabaseCleanupManagerImpl(
    private val appDispatchers: AppDispatchers,
    private val configurationRepository: ConfigurationRepository,
    private val infectionMessageDao: InfectionMessageDao,
    private val quarantineRepository: QuarantineRepository,
    private val coronaDetectionRepository: CoronaDetectionRepository,
    private val nearbyRecordDao: NearbyRecordDao
) : DatabaseCleanupManager, CoroutineScope {

    companion object {
        const val THRESHOLD_REMOVE_INCOMING_GREEN_MESSAGES = 3L //in days
    }

    override val coroutineContext: CoroutineContext
        get() = appDispatchers.Default

    init {
        cleanupContacts()
        cleanupInfectionMessages()
    }

    override suspend fun removeReceivedGreenMessages() {
        infectionMessageDao.removeReceivedInfectionMessagesOlderThan(MessageType.Revoke.Suspicion, ZonedDateTime.now())
    }

    override suspend fun removeSentYellowMessages() {
        infectionMessageDao.removeSentInfectionMessagesOlderThan(MessageType.InfectionLevel.Yellow, ZonedDateTime.now())
    }

    private fun cleanupContacts() {
        cleanupNearbyRecords()
        cleanupContactEvents()
    }

    private fun cleanupNearbyRecords() {
        launch {
            val configuration = configurationRepository.observeConfiguration().blockingFirst()
            val quarantineState = quarantineRepository.observeQuarantineState().blockingFirst()

            if (quarantineState is QuarantineStatus.Jailed.Limited && quarantineState.byContact.not()) {
                val thresholdProbablySick = ZonedDateTime.now().minusHours(configuration.selfDiagnosedQuarantine?.toLong() ?: Long.MAX_VALUE)
                nearbyRecordDao.removeContactOlderThan(thresholdProbablySick)
            } else {
                val threshold = ZonedDateTime.now().minusHours(configuration.warnBeforeSymptoms?.toLong() ?: Long.MAX_VALUE)
                nearbyRecordDao.removeContactOlderThan(threshold)
            }
        }
    }

    private fun cleanupInfectionMessages() {
        cleanupReceivedInfectionMessages()
        cleanupSentInfectionMessages()
    }

    private fun cleanupReceivedInfectionMessages() {
        launch {
            val configuration = configurationRepository.observeConfiguration().blockingFirst()

            val thresholdRedMessages = ZonedDateTime.now().minusHours(configuration.redWarningQuarantine?.toLong() ?: Long.MAX_VALUE)
            infectionMessageDao.removeReceivedInfectionMessagesOlderThan(MessageType.InfectionLevel.Red, thresholdRedMessages)

            val thresholdYellowMessages = ZonedDateTime.now().minusHours(configuration.yellowWarningQuarantine?.toLong() ?: Long.MAX_VALUE)
            infectionMessageDao.removeReceivedInfectionMessagesOlderThan(MessageType.InfectionLevel.Yellow, thresholdYellowMessages)

            val thresholdGreenMessages = ZonedDateTime.now().minusDays(THRESHOLD_REMOVE_INCOMING_GREEN_MESSAGES)
            infectionMessageDao.removeReceivedInfectionMessagesOlderThan(MessageType.Revoke.Suspicion, thresholdGreenMessages)
        }
    }

    private fun cleanupSentInfectionMessages() {
        launch {
            val configuration = configurationRepository.observeConfiguration().blockingFirst()

            infectionMessageDao.removeSentInfectionMessagesOlderThan(MessageType.Revoke.Suspicion, ZonedDateTime.now())

            val thresholdYellowMessages = ZonedDateTime.now().minusHours(configuration.yellowWarningQuarantine?.toLong() ?: Long.MAX_VALUE)
            infectionMessageDao.removeSentInfectionMessagesOlderThan(MessageType.InfectionLevel.Yellow, thresholdYellowMessages)

            val thresholdRedMessages = ZonedDateTime.now().minusHours(configuration.redWarningQuarantine?.toLong() ?: Long.MAX_VALUE)
            infectionMessageDao.removeSentInfectionMessagesOlderThan(MessageType.InfectionLevel.Red, thresholdRedMessages)
        }
    }

    private fun cleanupContactEvents() {
        launch {
            coronaDetectionRepository.deleteOldEvents()
        }
    }
}
