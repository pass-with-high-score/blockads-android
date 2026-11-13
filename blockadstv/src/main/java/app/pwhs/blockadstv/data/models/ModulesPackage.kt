package app.pwhs.blockadstv.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model for modular rules loaded from modules.json (remote OTA or local assets).
 */
@Serializable
data class ModulesPackage(
    val version: Long = 0,
    val updatedAt: Long = 0,
    val modules: List<WebModule> = emptyList(),
    val domainIndex: Map<String, String> = emptyMap(),
    val globalBlockPaths: List<String> = emptyList(),
    val domains: Map<String, DomainModuleRules> = emptyMap(),
    @SerialName("global_block_paths") val globalPathsSnake: List<String> = emptyList(),
)

@Serializable
data class DomainModuleRules(
    val css: List<String> = emptyList(),
    @SerialName("block_paths") val blockPaths: List<String> = emptyList(),
)

@Serializable
data class ModuleRulesPackage(
    val name: String = "",
    @SerialName("rule_count") val ruleCount: Int = 0,
    val domains: Map<String, DomainModuleRules> = emptyMap(),
    @SerialName("global_css") val globalCss: List<String> = emptyList(),
    @SerialName("global_block_paths") val globalBlockPaths: List<String> = emptyList(),
)

@Serializable
data class WebModule(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),
    @SerialName("mock_paths") val mockPaths: List<MockPathRule> = emptyList(),
    @SerialName("block_paths") val blockPaths: List<String> = emptyList(),
    @SerialName("cosmetic_css") val cosmeticCss: List<String> = emptyList(),
    val scriptlets: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
)

@Serializable
data class MockPathRule(
    val pattern: String = "",
    val status: Int = 200,
    val contentType: String? = null,
    val body: String? = null,
)
