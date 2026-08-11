package s1m.hwfido2provider

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
val base64 = Base64
    .UrlSafe
    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
