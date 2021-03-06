package io.github.nefilim.gradle.semver

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.some
import io.github.nefilim.gradle.semver.domain.GitRef
import io.github.nefilim.gradle.semver.domain.SemVerError
import net.swiftzer.semver.SemVer
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

private val logger = Logging.getLogger(Logger.ROOT_LOGGER_NAME)

// move to value classes once Gradle moves to 1.6
typealias PreReleaseLabel = String
typealias BuildMetadataLabel = String

interface VersionCalculator {
    fun calculateVersion(): Either<SemVerError, SemVer>
}

interface ContextProviderOperations {
    fun currentBranch(): Option<GitRef.Branch>
    fun branchVersion(currentBranch: GitRef.Branch, targetBranch: GitRef.Branch): Either<SemVerError, Option<SemVer>>
    fun commitsSinceBranchPoint(currentBranch: GitRef.Branch, targetBranch: GitRef.Branch): Either<SemVerError, Int>
}

interface SemVerContext {
    fun property(name: String): Any?
    fun env(name: String): String?
    val ops: ContextProviderOperations
}

typealias VersionModifier = SemVer.() -> SemVer
typealias VersionQualifier = SemVerContext.(current: GitRef.Branch) -> Pair<PreReleaseLabel, BuildMetadataLabel>


data class BranchMatchingConfiguration(
    val regex: Regex,
    val targetBranch: GitRef.Branch,
    val versionQualifier: VersionQualifier,
    val versionModifier: VersionModifier = { nextPatch() },
)

typealias VersionCalculatorStrategy = List<BranchMatchingConfiguration>

data class VersionCalculatorConfig(
    val tagPrefix: String,
    val initialVersion: SemVer = SemVer(0, 0, 1),
    val overrideVersion: Option<SemVer> = None,
    val branchMatching: VersionCalculatorStrategy = FlowVersionCalculatorStrategy { nextPatch() }, // first one matched will apply, put least specific last
) {
    companion object {
        internal val DefaultVersion = SemVer(0, 1, 0, null, null)
        internal const val DefaultTagPrefix = "v"
    }

    fun withBranchMatchingConfig(branchMatching: List<BranchMatchingConfiguration>): VersionCalculatorConfig {
        return this.copy(branchMatching = branchMatching)
    }
}

fun getTargetBranchVersionCalculator(
    contextProviderOperations: ContextProviderOperations,
    config: VersionCalculatorConfig,
    context: SemVerContext,
    currentBranch: GitRef.Branch,
): VersionCalculator = object: VersionCalculator {

    private fun previousVersion(): Either<SemVerError, SemVer> {
        return config.branchMatching.firstOrNull {
            it.regex.matches(currentBranch.name)
        }?.let { bmc ->
            logger.info("using BranchMatchingConfiguration: $bmc for previousVersion() with currentBranch $currentBranch")
            contextProviderOperations.branchVersion(currentBranch, bmc.targetBranch).map {
                logger.info("branch version for current $currentBranch and target ${bmc.targetBranch}: $it")
                it.getOrElse {
                    logger.warn("no version found for target branch ${bmc.targetBranch}, using initial version")
                    config.initialVersion
                }
            }
        } ?: run {
            logger.warn("no match found for $currentBranch in ${config.branchMatching}, using initial version as previous version")
            SemVerError.MissingBranchMatchingConfiguration(currentBranch).left()
        }
    }

    private fun versionModifier(current: SemVer): SemVer {
        return config.branchMatching.firstOrNull {
            it.regex.matches(currentBranch.name)
        }?.let {
            logger.info("using BranchMatchingConfiguration: $it for versionModifier() with currentBranch $currentBranch")
            val fn = it.versionModifier
            current.fn()
        } ?: run {
            logger.warn("no match found for $currentBranch in ${config.branchMatching}, using initial version as modified version")
            config.initialVersion
        }
    }

    private fun versionQualifier(current: SemVer): SemVer {
        return config.branchMatching.firstOrNull {
            it.regex.matches(currentBranch.name)
        }?.let {
            logger.info("using BranchMatchingConfiguration: $it for versionQualifier() with currentBranch $currentBranch")
            val fn = it.versionQualifier
            context.fn(currentBranch).let {
                current.copy(
                    preRelease = it.first.ifBlank { null },
                    buildMetadata = it.second.ifBlank { null }
                )
            }
        } ?: run {
            logger.warn("no match found for $currentBranch in ${config.branchMatching}")
            current
        }
    }

    override fun calculateVersion(): Either<SemVerError, SemVer> {
        return previousVersion().map {
            versionQualifier(versionModifier(it))
        }
    }
}

fun SemVerContext.preReleaseWithCommitCount(currentBranch: GitRef.Branch, targetBranch: GitRef.Branch, label: String): String {
    return ops.commitsSinceBranchPoint(currentBranch, targetBranch).fold({
        logger.warn("Unable to calculate commits since branch point on current $currentBranch")
        label
    }, {
        "$label.$it"
    })
}

// sticking to nullable rather than Option here so not to leak arrow in the public interface
internal fun versionModifierFromString(modifier: String): Option<VersionModifier> {
    return when (val mod = modifier.trim().lowercase()) {
        "major" -> SemVer::nextMajor.some()
        "minor" -> SemVer::nextMinor.some()
        "patch" -> SemVer::nextPatch.some()
        else -> {
            logger.error("unknown version modifier [$mod]")
            None
        }
    }
}

fun FlowVersionCalculatorStrategy(versionModifier: VersionModifier) = listOf(
    BranchMatchingConfiguration("""^main$""".toRegex(), GitRef.Branch.Main, { "" to "" }, versionModifier),
    BranchMatchingConfiguration("""^develop$""".toRegex(), GitRef.Branch.Main, { preReleaseWithCommitCount(it, GitRef.Branch.Main, "beta") to "" }, versionModifier),
    BranchMatchingConfiguration("""^feature/.*""".toRegex(), GitRef.Branch.Develop, { current -> preReleaseWithCommitCount(current, GitRef.Branch.Main, current.sanitizedNameWithoutPrefix()) to "" }, versionModifier),
    BranchMatchingConfiguration("""^hotfix/.*""".toRegex(), GitRef.Branch.Main, { preReleaseWithCommitCount(it, GitRef.Branch.Main, "rc") to "" }, versionModifier),
)

fun FlatVersionCalculatorStrategy(versionModifier: VersionModifier) = listOf(
    BranchMatchingConfiguration("""^main$""".toRegex(), GitRef.Branch.Main, { "" to "" }, versionModifier),
    BranchMatchingConfiguration(""".*""".toRegex(), GitRef.Branch.Main, { preReleaseWithCommitCount(it, GitRef.Branch.Main, it.sanitizedNameWithoutPrefix()) to "" }, versionModifier),
)