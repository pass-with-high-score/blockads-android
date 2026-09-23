package app.pwhs.blockads.utils

import android.os.Build
import java.util.Locale

/**
 * Detects device manufacturer and Android version to provide accurate,
 * device-specific instructions for installing CA certificates.
 */
object DeviceManager {

    enum class Manufacturer {
        SAMSUNG,
        GOOGLE,
        XIAOMI,
        OPPO_REALME,
        VIVO,
        HUAWEI_HONOR,
        MOTOROLA,
        GENERIC
    }

    val currentManufacturer: Manufacturer by lazy {
        val m = Build.MANUFACTURER.lowercase(Locale.US)
        val b = Build.BRAND.lowercase(Locale.US)
        when {
            m.contains("samsung") || b.contains("samsung") -> Manufacturer.SAMSUNG
            m.contains("google") || b.contains("google") -> Manufacturer.GOOGLE
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> Manufacturer.XIAOMI
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> Manufacturer.OPPO_REALME
            m.contains("vivo") || m.contains("iqoo") -> Manufacturer.VIVO
            m.contains("huawei") || m.contains("honor") -> Manufacturer.HUAWEI_HONOR
            m.contains("motorola") || m.contains("moto") -> Manufacturer.MOTOROLA
            else -> Manufacturer.GENERIC
        }
    }

    val currentBrandName: String
        get() = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    /**
     * Returns a list of steps tailored to the current device and Android version.
     */
    fun getInstallSteps(): List<String> {
        val sdk = Build.VERSION.SDK_INT
        return when (currentManufacturer) {
            Manufacturer.SAMSUNG -> when {
                sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                    "Mở Cài đặt hệ thống (Settings)",
                    "Chọn Bảo mật và quyền riêng tư (Security and privacy)",
                    "Cuộn xuống chọn Cài đặt bảo mật khác (More security settings)",
                    "Chọn Cài đặt từ bộ nhớ thiết bị (Install from device storage)",
                    "Chọn Chứng chỉ CA (CA certificate) và xác nhận 'Vẫn cài đặt'",
                    "Chọn file BlockAds-RootCA.crt vừa tải về"
                )
                sdk >= Build.VERSION_CODES.S -> listOf(
                    "Mở Cài đặt hệ thống (Settings)",
                    "Chọn Sinh trắc học và bảo mật (Biometrics and security)",
                    "Chọn Cài đặt bảo mật khác (Other security settings)",
                    "Chọn Cài đặt từ bộ nhớ thiết bị (Install from device storage)",
                    "Chọn Chứng chỉ CA (CA certificate) và xác nhận cảnh báo",
                    "Chọn file BlockAds-RootCA.crt vừa tải về"
                )
                else -> listOf(
                    "Mở Cài đặt hệ thống (Settings)",
                    "Chọn Sinh trắc học và bảo mật (Biometrics and security)",
                    "Chọn Cài đặt bảo mật khác > Cài đặt từ bộ nhớ",
                    "Chọn Chứng chỉ CA (CA certificate)",
                    "Chọn file BlockAds-RootCA.crt vừa tải về"
                )
            }

            Manufacturer.GOOGLE, Manufacturer.MOTOROLA -> when {
                sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                    "Mở Cài đặt hệ thống (Settings)",
                    "Chọn Bảo mật & quyền riêng tư (Security & privacy)",
                    "Chọn Cài đặt bảo mật khác (More security settings)",
                    "Chọn Mã hóa & thông tin xác thực (Encryption & credentials)",
                    "Chọn Cài đặt chứng chỉ > Chứng chỉ CA (Install a certificate > CA certificate)",
                    "Bấm 'Vẫn cài đặt' và chọn file BlockAds-RootCA.crt"
                )
                else -> listOf(
                    "Mở Cài đặt hệ thống (Settings)",
                    "Chọn Bảo mật (Security) > Mã hóa & thông tin xác thực (Encryption & credentials)",
                    "Chọn Cài đặt chứng chỉ (Install a certificate)",
                    "Chọn Chứng chỉ CA (CA certificate) và xác nhận",
                    "Chọn file BlockAds-RootCA.crt vừa tải về"
                )
            }

            Manufacturer.XIAOMI -> listOf(
                "Mở Cài đặt (Settings)",
                "Chọn Mật khẩu & bảo mật (Passwords & security)",
                "Chọn Quyền riêng tư (Privacy) > Mã hóa & thông tin xác thực",
                "Chọn Cài đặt chứng chỉ > Chứng chỉ CA (CA certificate)",
                "Xác nhận cảnh báo và chọn file BlockAds-RootCA.crt"
            )

            Manufacturer.OPPO_REALME -> listOf(
                "Mở Cài đặt (Settings)",
                "Chọn Bảo mật (Security) > Cài đặt bảo mật khác",
                "Chọn Lưu trữ thông tin xác thực (Credential storage)",
                "Chọn Cài đặt từ bộ nhớ thiết bị > Chứng chỉ CA",
                "Chọn file BlockAds-RootCA.crt vừa tải về"
            )

            Manufacturer.VIVO -> listOf(
                "Mở Cài đặt (Settings)",
                "Chọn Bảo mật (Security) > Mã hóa & thông tin xác thực",
                "Chọn Cài đặt chứng chỉ > Chứng chỉ CA",
                "Chọn file BlockAds-RootCA.crt vừa tải về"
            )

            else -> listOf(
                "Mở Cài đặt hệ thống (Settings)",
                "Tìm kiếm 'Chứng chỉ' hoặc 'Certificate' trong thanh tìm kiếm",
                "Chọn 'Chứng chỉ CA' hoặc 'Cài đặt từ bộ nhớ'",
                "Bấm xác nhận 'Vẫn cài đặt' nếu có cảnh báo",
                "Chọn file BlockAds-RootCA.crt từ thư mục Downloads"
            )
        }
    }
}
