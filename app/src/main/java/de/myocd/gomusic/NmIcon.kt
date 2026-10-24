package de.myocd.gomusic

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网易云曲库角标。
 *
 * 内嵌一张小图标（base64），在封面右下角按 10% 大小叠加显示，
 * 用来区分网易云曲库与本站曲库 —— 不再在歌名前加「(网)」。
 */
object NmIcon {
    /** 图标本体：base64(WebP) */
    private const val B64 =
        "UklGRnoLAABXRUJQVlA4WAoAAAAQAAAAGQEAGQEAQUxQSIoFAAABoINs26tIe8+IG9QidCNW2ilOJWzqk5VYBdxga+kIIoh2gmX6" +
        "o1PYhkoQrEZk866IExs3iQ7MgEM6k86c4/m+YpJp53v/OBExATCh3C21znTP4Q1EEpl8qVJ731xtdza2tnf5oxNBlAayTAj9SRIi" +
        "ywNJFE6O+N3trY1Oe7X5vlYp5TOJSMDruGfSqW9x8LNU6G2P/fFctdXu9fcFQq+xRNjv99qtai7uf2zTK6ad1uaMFRodntBrNuE7" +
        "jULMadNOK43Fnap3RXqNF7v1lNuimTpqe7jcFSkDit1y2K6eIpzBV9gcUGYcbBZ8Bm46zJiDjWPKmMeNoHlm8jhTdF2iDCqtR03c" +
        "hBmCKzJlVHklaJiku67XEmVY6bXr7qRw1oU9yrh7C1ZuIlTPlwllXrL8XDUBxixPmZjPGseNe1iTKSPLtYfcWCk93yhDf/Mox0gT" +
        "6lOm7oc0Y6NNHlLGPkxqx0SfFihzC2n9WOh/FymDi7/rx0CbFimTi2ntyDTJU8rop0nNiJShQ8rshyHlaDx9yvB9z0gefaVM//XR" +
        "CIyLlPEXjUNTZQnrkaxqWM95yvz88yFZlykCLluHospRFMyphuHaw4E91xBmX1MkfD17teAACwbBK5lWKBqumK4SJXhAolcwr1NE" +
        "XDdfbl7GBHn+UobPFBU/Gy7jO8aFY98l1EWKjEX1RfZNbNi0XxSWsUEOX6ApU3Qsa86zdPGhaznPLeKD6D4vRREydY62jhF17Zm5" +
        "LkZ05844JYwQnWdiFCVjAKAo4kRRAaBv4ERDD2Dr4ETHBvCYxwn+MYCfIqUfuDhWxLlbOazI3VJXsaKq1rWwoqUztbGibbrXw4re" +
        "PUcfK/oO7z5W7HsDAlYIgQjBChJJULRMZPAik8eLfAkvShW8qNTwovYeL9438aK5hhdrbbxod/Cis4EXG1t4sbWNF9u7eLHL4wV/" +
        "hBdHJ3hxIuCFIOKFKOGFNMCLgYwXMmYQvCD0xv83/r/x/43/b/x/4/8b//9NmRDEkPFCxowBXgwkvJBEvBAFvBBO8OLkCC+OeLzg" +
        "d/FidxsvtrfwYmsDLzY6eNFp40V7FS9Wm3jRfI8X72t4sVjBi0oJL0p5vMhn8CKTwItEhGAFiQQErBAC3n2s2Pc6+ljRd9zrYUXv" +
        "nqmNFW2TroUVLZ26ihVV9a0cVuRucXGsiHPgxwo/wGMeJ/jHALYOTnRsAPoGTjT0AIoCThQUABDDiRgAgFPECNF5Zq6LEd25M9o6" +
        "RtS1ZyCFESk41y3ig+g+z9LFh67lPE0ZH8qa8yAsY4Mchgvtm9iwab9IXcSGovoi8B3jwrEPLmn4jAufDZeBeRkT5Hm4tHkdE9bN" +
        "l4MowQMShSuaVvBgxXQVCA6wYBCEK8++xoLXs1cD1x4O7LlgiKocDuRUwwDrMgYsW2G4z3n245/DkFVZwnokqxoWGBdZb9EIw3/0" +
        "le2+PoJRevos1/fASJWhQ3Y7DClHA5rkKaudJjUwam1aZDMxrYXR638XWUz8XQ/jqE8L7CWk9TCe2uQhax0mtTCumlCfrfohDYyv" +
        "0vONpb55lDDO3MOazEpy7SEHY27M8mzEZ40w/qrny4R9yPJzFUwiZ13YY529BSsHE3rX9VpiGem16y5MsCG4IrOKvBI0wGRzpui6" +
        "xCLSetTEwcTPmIONY9Y4bgTNMzAVOYOvsDlgh8FmwWfgYHqq7eFyV2QBsVsO29UwbTUWd6rela5zUreecls0MKW1Nmes2Ojw5LpF" +
        "+E6jGHPatDDlFXrbY388V221e/19gVxniLDf77Vb1Vzc/9imV8DPkrul1pnuObyBSCKTL1Vq75tr7c7G1vYuf3QiiNJAlsnPg8jy" +
        "QBKFkyN+d3tro9Nea76vVUr5TCIS8DrumXTqWxxMKFZQOCDKBQAAMEAAnQEqGgEaAT/N4udsv7gvKazSSJPwOYlibuDx7wC7Y57P" +
        "AWc9UxPwzRXVPyXsbS/gDlkZdI0X6OgBklqdtuWTggoDBkmW/mZWTRpwONMjoAYFDg//rJMd+kANtQgCC0Zb3DRO+1zFXBG6pB2A" +
        "FOwicOmX792z+MqWBJazbwBOu5R8n8yK+q16Ytr5r/78x9AoCl9EQTI7LzivnslvaNy3d2mVd961rplhGc8uf9xxYgNjhDENXhaF" +
        "yPLiDU+2yMtIKs755CUyOEPLS9Xuac4kjn/7zO/Uz7O2GzOhqC5mxa1ZajDyZJhFgCAGDaNso8HRIkurZ4C0BvtbQNDd3VAO7CU9" +
        "AhvbLnxM+lSFJIntIug/xeyNqRgySpcDnrZ/PMHK/expX0dFf4w6M0g+P1ppeQC09VlB4+Gxg1L/N2rIkbCq3E/H/hvDUjEZw6eS" +
        "jMhDY6GdQJun6KgCJX5lQZUV6B/uO8jsbL5r3ObxQJoXF5+V8saTc2ZnxJOexqQxP+fmJ7EIuxSVlmwn5Q6HABlaE21LITH2NRVH" +
        "ADOHzI/h2BzcOQ/UrFR8tp73rVA+wnA+Oekk0bbQ3MxDlAeAyZ+Ze4EhgLIIgs1KhlaDYiw/R64cgFPCQ7rFqNdR3+e7Twaji86t" +
        "OdMAkUzCx0lUgDl4JFImMlhtKDsD8onGw/OLfyHU4Xkk0KAAAP6xqvN94ivqZekuwpaJXLpbsFYDJ+qYb7xRt/2E6WErC+paVsAc" +
        "qM9ZtQKkibjX776fbb3oC4B5T/7L8MOo/yGyirYMkE8/FRqmohW1h5h0m1LwInx6iNuGiE+fd0xvpimg0s1tu5rpmz53McHo8x3i" +
        "qYOIpGhuwKu7NmAVV98X0lnvxI8njN/3Q7D29GycivXuWlB9PcDW+eD+Fcn0v/4AAjh0kGKjK+asWi0uv98Htsc5dosbdzV5lRhi" +
        "hPEdySV9z4ksUtk6MbYr0JwOsEEk9UaKz+JloZvAShzUaW5CGUo57gLZ1AApK6IYb/s/vRlchMgvjzFNRcDIuMq0a3+bOfqMdO0s" +
        "Nr3MHkYinUN2E+quVXwH4w9b+Y9H/FlUZe6SDlQhgRG7NPL97sMrRJoUVnPc/4L0pkN6Nm4CbIMFS735osDxn5KiKirQI8lou3xv" +
        "vUOAQQSPKcahC+1cDAkrCGs2L3cqXWMOEuUhIJ2Mb1qrBLwHGUgqdtmfy7S7jyGQae2+xu/lBeSuQ6fbfuCGSEqUwzAN8YVZtQce" +
        "vVkoaUyTcoIeAaTHu7doUT7jtBPXO5OlU5R7V1x9mYkZpMc0rS9DPa0uI3xsgfCAWdiiUJWpBRooxCT3Wze5vL70ai1Sdm1xIFql" +
        "CpmXreiJR5Q04BfwZgYtKECNhkFDA0t7PjRaGBA7XW3q99l1+2Oap9rbP/xEt3AJjjkIbgC/oT8zawQgNvs35reZbc6AQz1zN6XM" +
        "AYCNeQIBsIMB0/NZMcftv73Q3IFMVI5eI8Ya2ZPmtvLHkYSNJAjyOIMkG09QWPcHbeO5FJTkpTTXY3eo3X+T/MLNiGpHZJr5i7Vc" +
        "NE+BzVE3NXExaI/n52xJWzbFdF9zlQBGJ8yz3BHiRFq/ap6W9yPVeAiQAFGtXXScyCeLkNoeDwlqYDL7i9wZr/X821C/IFt7g2aV" +
        "Cwh8McQ4ZPYXku5qnSKDV67BvoX+KpPmv8d54EWxenBPOVzq7p/3dD3wseSsHa+lLS7sTiC2/rzzxSeiZs2vHCp7z8opFXEURalw" +
        "Gc/wDX0rC+VI1U2vCdzl/YAWnTS+74vDEg4uDC0DL2QBqQ51PZyI+B1wFX70Yt1InA4m0Lcx2LItGNrFidYVQt1w17VE5YTFo/NG" +
        "S/V7c+qDnH/EKHz6G4VdlHDXLeH+aOc8qY6Kn/8NU6blXqFd3ljTd6Z0/VbvtDrIs/KemLf/MncRquKvt3HqGVpkkF9zOCUSReU+" +
        "tm8ZN77vTMouLDWguJ9kMW0MQAAA"

    /** 解码后的位图，只解一次 */
    private var cached: android.graphics.Bitmap? = null

    suspend fun bitmap(): android.graphics.Bitmap? {
        cached?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(B64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.also { cached = it }
        }
    }

    @Composable
    fun rememberBitmap(): android.graphics.Bitmap? {
        val bmp by produceState<android.graphics.Bitmap?>(null) { value = bitmap() }
        return bmp
    }
}

/**
 * 带来源角标的封面：网易云来源在右下角叠一个 10% 大小的图标，
 * 其余来源按普通封面显示。
 */
@Composable
fun CoverWithSource(
    cover: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    circle: Boolean = false,
    source: String = SRC_LOCAL,
) {
    if (source != SRC_NETEASE) {
        Cover(cover, modifier, radius, circle)
        return
    }
    val bmp = NmIcon.rememberBitmap()
    Box(modifier) {
        Cover(cover, Modifier.fillMaxSize(), radius, circle)
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "网易云曲库",
                // 占封面 20%（原 10% 的两倍）
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxSize(0.20f),
            )
        }
    }
}
