package app.pwhs.blockads.ui.customrules.data

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    TXT("txt", "text/plain")
}
