package app.pwhs.blockads.data.models

enum class FilterRuleKind {
    DOMAINS,
    IPS,
    COSMETIC,
    COSMETIC_EXCEPTIONS,
    INJECT_CSS,
    SCRIPTLETS,
    SCRIPTLET_EXCEPTIONS,
    BLOCK_PATHS,
    REMOVE_PARAMS
}
