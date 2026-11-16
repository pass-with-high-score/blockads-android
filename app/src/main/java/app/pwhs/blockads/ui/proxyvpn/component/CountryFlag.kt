package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.geoip.GeoIpLookup

object CountryFlagRegistry {
    fun getFlagResId(countryCode: String?): Int? {
        if (countryCode == null) return null
        return when (countryCode.uppercase()) {
            "AE" -> R.drawable.flag_ae
            "AR" -> R.drawable.flag_ar
            "AT" -> R.drawable.flag_at
            "AU" -> R.drawable.flag_au
            "BE" -> R.drawable.flag_be
            "BG" -> R.drawable.flag_bg
            "BR" -> R.drawable.flag_br
            "CA" -> R.drawable.flag_ca
            "CH" -> R.drawable.flag_ch
            "CL" -> R.drawable.flag_cl
            "CN" -> R.drawable.flag_cn
            "CO" -> R.drawable.flag_co
            "CY" -> R.drawable.flag_cy
            "CZ" -> R.drawable.flag_cz
            "DE" -> R.drawable.flag_de
            "DK" -> R.drawable.flag_dk
            "EE" -> R.drawable.flag_ee
            "ES" -> R.drawable.flag_es
            "FI" -> R.drawable.flag_fi
            "FR" -> R.drawable.flag_fr
            "GB" -> R.drawable.flag_gb
            "GR" -> R.drawable.flag_gr
            "HK" -> R.drawable.flag_hk
            "HU" -> R.drawable.flag_hu
            "ID" -> R.drawable.flag_id
            "IE" -> R.drawable.flag_ie
            "IL" -> R.drawable.flag_il
            "IN" -> R.drawable.flag_in
            "IS" -> R.drawable.flag_is
            "IT" -> R.drawable.flag_it
            "JP" -> R.drawable.flag_jp
            "KR" -> R.drawable.flag_kr
            "LT" -> R.drawable.flag_lt
            "LU" -> R.drawable.flag_lu
            "LV" -> R.drawable.flag_lv
            "MT" -> R.drawable.flag_mt
            "MX" -> R.drawable.flag_mx
            "MY" -> R.drawable.flag_my
            "NL" -> R.drawable.flag_nl
            "NO" -> R.drawable.flag_no
            "NZ" -> R.drawable.flag_nz
            "PH" -> R.drawable.flag_ph
            "PL" -> R.drawable.flag_pl
            "PT" -> R.drawable.flag_pt
            "RO" -> R.drawable.flag_ro
            "RU" -> R.drawable.flag_ru
            "SA" -> R.drawable.flag_sa
            "SE" -> R.drawable.flag_se
            "SG" -> R.drawable.flag_sg
            "TH" -> R.drawable.flag_th
            "TR" -> R.drawable.flag_tr
            "TW" -> R.drawable.flag_tw
            "UA" -> R.drawable.flag_ua
            "US" -> R.drawable.flag_us
            "VN" -> R.drawable.flag_vn
            "ZA" -> R.drawable.flag_za
            else -> null
        }
    }
}

@Composable
fun CountryFlag(
    countryCode: String?,
    modifier: Modifier = Modifier
) {
    val flagRes = CountryFlagRegistry.getFlagResId(countryCode)

    if (flagRes != null) {
        // High-definition circular flag image
        Image(
            painter = painterResource(id = flagRes),
            contentDescription = countryCode,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        )
    } else if (!countryCode.isNullOrEmpty()) {
        // Fallback to emoji flag
        val flagEmoji = GeoIpLookup.countryCodeToEmoji(countryCode)
        Box(
            modifier = modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = flagEmoji,
                fontSize = 17.sp,
                textAlign = TextAlign.Center
            )
        }
    } else {
        // Sleek circular server glyph
        Box(
            modifier = modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
