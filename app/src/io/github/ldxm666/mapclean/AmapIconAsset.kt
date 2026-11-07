package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
* 高德地图**原生启动图标**（{@code com.autonavi.minimap} 的 {@code drawable-xxxhdpi/v3_icon.png}，192x192 PNG）。
*
* <h3>为什么内嵌</h3>
* 装了 App 由 {@code MainActivity.appIcon()} 走 loadIcon 取真图标；**未安装**时设备上没有任何图标可取。
* 内嵌一份原图，「未安装」那行也能显示高德自己的图标，而不是品牌色占位。
*/
class AmapIconAsset private constructor() {

    companion object {
        @Volatile
        private var cached: Bitmap? = null

        @Volatile
        private var failed = false

        @JvmStatic
        fun bitmap(): Bitmap? {
            if (cached != null || failed) return cached
            synchronized(AmapIconAsset::class.java) {
                if (cached != null || failed) return cached
                try {
                    val raw = Base64.decode(B64, Base64.DEFAULT)
                    val b = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (b == null) {
                        failed = true
                        return null
                    }
                    cached = b
                } catch (t: Throwable) {
                    failed = true
                }
                return cached
            }
        }

        @JvmStatic
        fun drawable(c: Context): Drawable? {
            val b = bitmap() ?: return null
            return BitmapDrawable(c.resources, b)
        }

        /** 192x192 PNG / 23993 bytes / base64 31992 chars. */
        private const val B64 = "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAAjIklEQVR42u19eZAc133e9153T8+1u8Be2AWwAJa7WNyXCFBkSFMUSUmWS6FMsyzaikNVDrpc" +
            "dtkVJUqcVFyulGMnUlyxZZsu2ZZ8yy47tixbtssJdZgmTYuCgAJvESAogAABkCCuPWbn6vde/njTMz093dPdcx/vV7UFLLA7szvzfe993+/3e79Hnsn/toAK" +
            "nxA4ZDyEETIFAYC08JEzuRwyhQIoIepl7mJQ9RKoUARQoUIRQIUKRQAVKhQBVKhQBFChQhFAhQpFABUqFAFUqFAEUKFCEUCFCkUAFSoUAVSoUARQoUIRQIUK" +
            "RQAVKhQBVKhQBFChQhFAhQpFABUqFAFUqFAEUKFCEUCFCkUAFSoAAAIAF4oAKoYsuACYAHQKGJokAhOKACqGBPhxHRiPC6wXgetZAp3Kz7uxG+jqbVHRKeCn" +
            "DCChC7xyTcPnX4zhr89S3MoKLE4CP3WkiEd3W1gtAJQoAqgYYOD/4UsEq2s6oAHQBE6dJ/hX53VkPpLHjx7K40aOQCOKACr63NwmdAn80zc0fO55B/BjAlpC" +
            "gAsCPcfA4hScUfzcswY+NF/EREKgyDqzEygCqGjbiv/dZYpfPRmvAT7j8utjKznwmA5CCDRd4OoKxbkbGrZss1BgagdQ0cfA/8ILJn7zFC0Dn8Y5uCBgDNA0" +
            "QF/Oy+9NaGVCdCMUAVS0TOp4Ad9e8bkggAAMk4BezwEArDETjEFevCCA6VGOLWMMRd7auxgUAVS0HPgWl+nMtCHw+k2K333JxB+8rOPqTVoF/PLqXgJ/cjUP" +
            "faWAW3Mj8v8IoFGAFQi2jDHMpgXyDCDKBKvo5Tz+BlPgzRWK//HNWqlDCKmWNQ7wi0t5FHYlwa3Ssm8HAw5OSkKpLJCKnl3xNxoV4Hut+LbU8QJ/8bs5aAdT" +
            "WLN0CC5qdM7ecVYmmiKAip4C/gZT4OIqxWdPxPAbpwx/qYNa8E/kc3jnlTxG98WxTmrBzziAmMCRKQ6Ld271VwRQEUrq1ABfg7fU8QH/2yfyGDsYx/qIiWK+" +
            "duWHAKZTAts2MuQYVBZIRW9IHb8VX67aLqnjDIvASEKC/3gOs4saVjaaKGZqwV9lgFMCOUu1QqjostS5vBZR6rhXfhv8J/IYnaJgcylkPMAPAEIIgBEcnJSp" +
            "1KxF1A6gojtS5/IawWdPmPjdl3RcfFcLJ3VcK39qFBjJSPAn00B8fxrvZin8twsZx2YYVCFMRceCCSCmSanzzjrB55838cSpCvBDSR0f8L9zKg+iAaO7TVy3" +
            "qGfGp0JCApgCS2PSABNFABWdkDpjgcCP8MAl8E9n1nH+VBGCATNHTaym4iiuBKDMIpge5dg5IQ0wIYoAKtosdbyAH0nq1AN/UWB2UUN+o4nMNQC6//ahUYAx" +
            "YGkCGE903gArAgyZ1LnaCqkTsPJPbKXIL6Rx4wYBNWQDXFAcnGJI6AKZIlEEUNE6qWOUjhpey7ZI6tQBPwAk00ByTwpvLROAiFDgB4C9E/KH6DT4FQEGVOoY" +
            "FJhMCNzMEfzWCyY+860WSB0X+McnBcZuZnHuRBEwAMKBbUdjeCOn1TW9VbsTB2AKHNlkdcUAKwIMmNTRCLAxLpvJfusFE792UsPpK3rzUicA/MgL7Lgjhosi" +
            "geJ6BFRZBHPjDFtHRMcrwIoAAyh1btYFfgue0AV+agI8K03v8sZEoOmtMcAAto8RbDClASZKAqloRuo4gd8SqeMH/uMFwCTgeWl6ye40blwND/6qFmiHATYU" +
            "AVREkTrL+TZKnVJQIsCLtFr2mASEA4k0MHkgiTPXCBp6Mg04NmN1zQArAvQh8MfjAmvFNksd527jBH9p5bdj29EY3ljTQ5tetwHWYgJL4xxZqzsGWBGgj6SO" +
            "Dfw/etXAr54ycOp8m6SOS/ZsnhUwr7rAXzK9V404isvRUWR3gG4eZ9iS5mCiO/pfEaBPpI4X8Fstdbxkjxv8lFZMb346gRtXSHTd7+gA3TNJMZ1Ex6fBKQL0" +
            "cBQ5yrMy6wO/jTuPH/gdpvdyI6bXFQcni9CpgOiaAFIEQC+NFyGQWZ2s1WGp4yF79PMZnHvZKsseYclK7+SBJM5eb+4pCCGABuybZF1/7RUBekDqEMgVvwr4" +
            "F+Vb006p4yd79PMZXHjZAk0QcAfhbNPLCs2t/oxJA7xvsrsGWBGgB2I0JmXPn7+exC8eRwX4sfZLHS/Z4wa/rfurTW/j4C8b4DGGrSMcBdY9A6wI0IWsDhfS" +
            "8MUMA4wX8ZdvJKqAT40OSR0f2XOhJHuc4G/W9HoVwPZMUmwwgZUCOjoFQhGgi+AHpMEFgK+8WsDPnUh4rPjtlTpRZA/PA6NTFKlDKZy9EL69OYoB5oIoAgxy" +
            "WFyu+LSE679/E/jZpzn+8VyiK1LHLXsWd3AUTq/XgN+u9M4eTeLs5WjtzWEqwO+ZpV2tACsCdEjq6FT+vQJ8CjDauaxOHdnjBL8te8qZKSZN78Vc86bXbYBH" +
            "EhZ2bch33QArAnRA6vz9m8Cnvynw5BkCMNqxrE6Q7PFb+W3dP18yvVE6PEMb4BGUDTAUAQZP6tQCn3SkgBVF9mReyODKWeYJ/pabXg8DPBLrbgVYEQCtKWAx" +
            "XpE6vQx8e7fxA3+N6b2I1oO/fAZYGmAmiCLAYEqdHgK+DX5BsLjNH/yEA8QE5u7UceYqRTv0GePSAB8ZlzOAug1+RYAmgG+/eSeuAP/1H3oU+CHBb5veXd8T" +
            "w/m1iMcaI/4sIwkLt03yjt4CowjQotXLBj0lfQB8p+ypA36n6V1JxpG50h7p4zTAM6nuV4AVARp4A0lpxf+FZzn+8jQBCj0K/BL4CSVY2BIM/tlFDdpcHJfP" +
            "k7bpfmcLtH1wX1ME6GGDKwicm/TJKuBTwKy+8rPn3DkBDt/G8PbxdV/ZU13ppW0Dv9sAd/oWGEWAiMBnAkjFLBiU4aULSfzicW/g9zL4jyzwuuAHAOo0vUS0" +
            "2TuRnjPAigB1gP/CpRn83nOH8NRrSaBAeh/4Dtlz+DaGC09ncP0t7gt+5AWW7jdxpRBvn+n1MMBL06UhuFAE6HHg7+gf4JcApmnAwfn64Heb3rYUu3wM8OIk" +
            "MJ2UryVRO0BvAv/pM9vBc7JXB3HZANbTwK8CPw8F/k6YXnjNAJoExkw5q9SgigA9CXyY8q7blnU+ov1NbVpMBILfaXpn7kji+e9SQOtsM5J9DWovha6AXwv8" +
            "QQQ/AMAAFu5O4swVDaLYuXe/XAGe6i0DPBQEGEjgO25i3D8XEvx5gd33m3gzqyGzgs5JH/sa1FGOLWO9ZYAHmgBu4J95dwL/5+Q+fPnU7v4GfhX4Gd74agYr" +
            "14V/qtNheq2NJm68IY09OlhALF+DmhawuJJA3QV+rI+B7wX+d3kg+GcXNWxYNHHqNO2OvykZ4LQhK8BKAnUQ+E++tITVNb3/gR8R/LbpTY4SzNyRxIvnpOnt" +
            "1u9uG2D7pJwigAJ+Q1cSLY1ZeOOr63Vljx1EA3bdn8KZK1pLjzVGNsAx0ZMGuK8JYAM/rjOM6NbgAt8F/tPfyGB9TbYx+IHflj677jfxjtUF0+s2wCmBbRsr" +
            "BliZYAX8toN/234d1kYTlztsemvOH1tUGuBU5RYYAdUK0TTwL9wawx88d2hwgd8A+G3dP7GVYuJgAqdO066B322ADQpkBKATtQMo4EcA/470Ok5/oxgK/PYs" +
            "n233psqmtxfi2AyDTkWPiZ8eJ0BY4JdbbQcpHOD/zteKEFYw+O1jjfPvM7pqemtaoGMCS2NyCK5tgJUEUsAPvIxuNpYtg19QQNQBv637d99v4qaId9f0un6X" +
            "6VGOhQkmj0D24MutK6nT++CvazTdpreTHZ4IqAAzYGlC3nJTYOi5FGhPEMAL+H/94hL+9FuHhmPF9wD/K08WQXgw+J0dnhMHEzh1tjfAj6ojkAxpQ+CaRRQB" +
            "wgD/y6d24/qN1PAA3wH+6WIOr36jCIJw4LePNS7cncTLFzV0ZdZiQOydkAWwKrM+zARwA//t1RS+dOpIDfAHWur4gP/0MwX5GoU9LFIElu6L4c2s1pljjYhY" +
            "ATYFjmyyUOTV8mcoTXDgil+6B2soVnwf8Ide9btwrLGR322udA2qbYBF6TrUodsBLE4CV/yhAr4L/K89VQCMaODvyrHGKAYYwPYxgo1xIGv1ZgaorQRwrvjp" +
            "mAJ+K8Bv39ZYnuXzFumZYpdnBXiKIaELZIpyCBYZFhNscQKdcmwwi7XAH0ap45rN3yj4bX9QnuUjRO8urRpwbMaqMcADTQBRAvSGeB7X15P4/ecOK+CjejZ/" +
            "M+C3jzV2bJZPEwZYiwksjfMaAzzwO0DcsPCl5/fhc//wHgV8D9kzdjOL104UI4HfWezqWdPrHoLrMsADTwAhCEbMPH796WP4wtdvH26N73MTo3k1i3PHC+Xb" +
            "16OAf2IrRWxXEhfO9y743UNwp5PArXz1DNBeQwJtFfjjhoUXLs3gC0/dDi0hQEtXYA71qu+4gLoR8NumNzlKMHNExxuXaE+DHx7XoPb8AtUSzScAgzL84xtz" +
            "AJOrwLAD3yl7GgW/HfPvM3DmVhKC9z6gCJFDcPdNsp43wC0jgEaAItdw9t1xBfqS7LHBn76UaRz8eYEdRwxcKZRubemDNYUxaYD3TVYbYEKGYAeYSGUV+h2y" +
            "J30pU76DN9KbQiX4t+3X5W2N10hfSB+NAmAEixsYNqe5PAKJ3tX/LSOAHce2X1botwg2zwro5xsDv/NYY2xXEpevkL7R/c5rUMfjcmGs+MQB3gF0KrBWiOF9" +
            "O8/jvoPn5HRlIqQUGDLZ0yz4CQeSaWDmiI5zl3u40lsnFjdY0KmA07L0qgTSW10E+5kPPwMAeOrFeVkDMIYjFWrLHhv8QTN76sW2o/K2xl441thIBfg9s7Rq" +
            "BhAhA26C5S8pUGQaUrEC/tfDX8Onf+hr2Ld5HTxHwYt0sHcDXrvyRwW/rft3HDFw1eihY40RDfBIwsLiWL7nK8BtqQTbJMhZwIf2nMXdCxfw/17dWakKx2R9" +
            "YGB2BCFN3+ZZAfHaGi6cZQ2nOu0Oz/x0oqcrvWGuQd0+2h8GuOUm2CaBRoBbORNCEDxy+BX86eNfwr994CRGYkzuCIIMxo4gKuC3L6NrKIqVDs/LV/tQ9rgM" +
            "8Eis2gAPfB3A6QNsL2CXv2/lTJi6hZ+499v44uN/hkfuelW2B/S7UeYEmzdVg79RzZ9MA7NHk9L09nkcnKquABMyJAQQPpJGI7I9+lbOxHhyHT/9wWfxO499" +
            "RWaLihV/0DdEEPLDCf5GNL9z9d92NIa3cjpYgfRFsavuLTDjvC8qwG2TQH4kkESgWMmZ2LXpGv7nR7+OX/n43/WXURZS9syNceSfXy2Dv6EX3i527dX61vR6" +
            "XYN62yRHzjECRYghIIAIMLRMVDShRhkyhRgyhRju2HEJv/HYn+BnHn4GE2MZeYGFQG8SoXQH79wGhvXvVK4kahT8tum1dqT6ptIbVAHePALMpDiKjhZo0uOb" +
            "Gm03+L1fMFYmQt7S8dDB18pGeSK9XkWEniCDkGmMrSMV8Dcje+xZPmR3ur9Nb1ULdP8Z4I5IoHovhkblrSErORMA8G/+2Sn8zie+gkfuerWcMSpXWbu98o9y" +
            "Cf7LovFsj2OWz9jhFN5dHqzi4F2z/dEC3TEC1AM/4xoY16r+7VbWxGg8h089+E/4tY//jTTKnHQvY1QC/1SCY/l5CX4YaNzwlkzv9gMGblha33R4hhqCqwFL" +
            "o/1lgAGAPJP/bdEO+eMHfsY1aJTVgL+MD0bKRyt1Cpy6sAl/8cIePPXKvGRsp1orSrJnOimQe3lNXklkNgn+Uofn2pb+1/01BjjG8NxjOcymRdUxyF73AHo3" +
            "frwg8ANAriiL1Ae2voO9m6/hnoW38OUTt+OVi0lAkz3njLd/5bfB34qVf2IrLYOfGnwgquF2BXhxEphKir4ywCUC8CaqvjK12Ww4ge+OlVwcAPDA7rM4tuOt" +
            "mvmhmobWEqEE/rRuYe2kvJUl8vQGn4srkntSeGtZrvwD1RxYvgYVuJGTt8GUU6A9/muS6+yttu/D68XGXoVsoZZciRhHtkDxhW9vwudOMjlB2hRyJeLNg1/T" +
            "gKTGwF7MIJuNMKczIObuTePyGgVjg6H7q3aALMETH8njJ46ZWMtzmH0k7YgQrS9VOPvA7b/bz8Ld1UPX1zm/1+kj7BvGmZBFtZQBvH4T+OVvWfjiKxpYlkBL" +
            "iJrHbWTlbyn4i8DMUROrqQEodsH/ENC3HweOzsr3Saf986MT3gABvL7DfV7bywS7gekGvRfgvR6Pcfn/SQMwNeDk28Cnvynw5JlSH1IsIhFKK386W8Dq6Vyo" +
            "iynCmt7ZRQ35hfRgmV6Pa1BfeJxiJiXfF+oYg0h6/iATKne31vsI9UCu/Cr1qhiGGq0Y0HMCucqsF4FrWWD/FPDFhwj++BELR+YssCwBKxBoNMRztgv8pQ7P" +
            "/EIaN24O5sqvlSrASxPATKr3boHveh2AB+wAzdQT7BdbI8BqQX48OK/jrx7R8cRH8pgbZ5IIrA4J2gR++1hjfH8aN5cHfDxMaQhuM+9vXxHATzC5JRAP6RO8" +
            "5E896ePnFwA5hSzHgI/tM/HkD2v4T+8TmE4JsKy8nbmKCCXw68v51q78pRjdbeKmReUsnwHnwN4JHuo971sCiLZVEMPLHz//4O46BYDrWQn2T76X4G8fpfjE" +
            "UUumS7MVJNrgL57Lg/MWgr8IzO3SsJqKD0ylt64cjQm8d6tZ9forCRQCwGFbJqKY7UrHqXy+61lgLA78wn06vvrDBN9/gJfZrC/nUXg931xxy6vYtZng+lQa" +
            "mYwYTNPrvgY1JbB9rLY+pAgQceUPQ4yokwE1AhSYNMrbx4DPfoDij7+fYV5kkX8t31LJY+v+wo6kBP+Ayx5pgOU1qFPJSvYHfXAOuCEC1EuWBgGz1eYoChE0" +
            "IjNGWUt6hAfndTy2xAEu5KGUVhFAB7SDKawzDcMUtgF244MM8w7gfjG0DuwzYWSUTqUsuvuQhuQogbBat/qP7IojR/SBq/QGxT1zpG8NcNsI4NaA7UqP+Xec" +
            "+n9PngE7phI4uF2DKLZgFygC45sI1kdMFPNiaMBvX4O6c1yvMsBE1QE6qEMbfLHjGvChQ61Z+UcnCG7NjQwV+MvXoI5yLPX5QHDa6KperwoMtFcCeT2fkwxB" +
            "z5djwOHdWlOjTGzdv7aQHrq7EGwDvGeSYtSsNsBCDCABSIOg9CNBo+XyRr/PSQ67hWL35gTuWNSAfGMyiHDAmDdBCBmKYpdXHJ72lqL99FK0LFPdjkyQRtp3" +
            "wDplSBn03EsN6v7NBLfGzKEzvVUE2MQ8p2vyPtoFdNLASUG/1dn5i1NXZsDeAZxEcK7oNtB1Wl0NtklgF7fs57H/dJKk6u8B5wOKXMogmCySDCIcGCnpfsaH" +
            "E/yMA1pCYO+UBL9B+68JrkwAi4dNbbIITXBabb+/D8o4aBm0QS3QQWcGvPqG/NqsVwvA/KYEtu1kkcaZEx1YmR+BoROw/HBKH1jyGtRNKQ3LDOBFgBp9SoBb" +
            "mdWWyx7hcyimPFytZBqdh2U4AsDNq6cQOMng9z3O1d4r0gbw8NZ1/MpL8XAdT5oG7eoFaN+4BHrHbTAm4igWRPkA/bAYYAbAHCX4/TUAa/39++iNSB8vpy9C" +
            "asBq8IuW2Ca3/Aq9kHFg59ExkK/nosmgM5egXXgbbP9O4OhWGCaRaVAMCREYMD8jNW22VEwkdNDToBH+v54eJESAEOH4GgJCKj+I83v9UpvO+UD219t/2nNI" +
            "nWFQ/3TokU0WZrZToIjAbBAVDLFbq9DjUuLRE68h9RfHMXpuBYZJoGltbJ3tsSG4k6PV7zdF/36g2UovmrlTtsGGa0pqwV+PeH4kSBvAnVvkyaZAyccBsZKp" +
            "bJ9xDWIlg+yTJ4C/exX6cl4SgXam/aNrMigmMDE2GBtepLeJBDTGkRBgJfYHESBl0JLyxRrur3eu6E5g2ZPi/EigkXAk4AL43j0WoIn6MkjToBdX6suiP/8n" +
            "iGcvgmZZ+COZfWiAx0aBDWmgwPuv9aEpAgjnwCNSnwROEIfbUUjdH8iv7SGIBEFyaN0C5hfimNhMgGITZiquwcoxxF5+HT//QYZ75nnwkcx+rAADGE8LpGNo" +
            "7VmKYegFchKCOPwA8ZFXfgTyAlTNoXziTx43CWZHOe5dIIEt0mRlFVaufjr48X+/H//ioIknPkTxmQ/mK0cyMSBEcBhgUbkpargkUJAXIBFIYBvjasA2Loe8" +
            "vEEQCcoyiNavBcRu+aeMrRzD+x+cwY99bBpX1oAiAx7ea+JPHi4dySwNkOpnWeRlgPu9o5J2ctWvR4Lqf6+VQ2GzQ15yyM8X2CTIMymDRicICPfPANlSx9Nk" +
            "T4/iP/yXxcrnGrCcA8YTwM9+j44//OcM9yxysEJlZAv6cAaQFhOY3jg4Gd/IbwNp4OuCvIAth2rIQapTVWFIEJQV8iJBkUsZ9MBeAlEg3jKIF6oyQO7V/1P/" +
            "eR8WphNYK0jw2yQoMuBGFjgyo+Pz30fxmQfymB7lVQf0+2cLqBjgPOt/A9ySHSCMIfbT6H6eoCprRBCpTuAlh9zSSCPeZvrgHIHnFqBpoMVcXenz2AfSuJGt" +
            "gN+5ExgasFaQHw/vNfG3j1J5QL+UVemH3cBugd4+wZHQK4XHocoCNdLuSiLKoaDdIGqxzM8cO7/PoEDOIrj7kIbRKep5VNLLAFs5VpY+BVYLfi8iLOeAdAz4" +
            "gTspPnqPvGeYFUjfFNAmNw7WmeeOrD1RSOCsGLtXmHokcJrjak8gfNOyThLkmJRBe2eovwzyiF/6+SXsnpHSJ0wYmuxXyhWBmQngB+8l+MCdQCpFAIv0nQEW" +
            "YkgJ4J4ZSkg0EtRLW3oVy5ySiPoQxytDJFusRc1zuzNLBgUMTXgelXS3QNir/8c/MY8P371RzsTXoh/QKVoSWHu3Az/yAeDILiZ/7l4kggCMJDA76c7gqR0g" +
            "lB/wkk5hfAEt+QLni+00x2ELbjoVga0TeYvg8G4NyTFRLYNcBtjKMSztGcOP/9gsMkV5hsH5ETVyliTgvYc1fOw+KYtgeYxz7LIBHkkKjCUrBlgoE+ztB4JI" +
            "EFUS1bZO2Ia5djfwk0N+aVLnqpxnwOIUr54Y4WOAf/KTO7F5LIU88x6/EpUIRS6JML2xWhaxAukpA2xqFdlDhCKAJ6ij7gZ+kqhelsh+BELq1wrccshNAvfz" +
            "xPVaGeQ0wLb0eejejbiWlfcT+LVpBO0Khl4x4fZHzpIfB27rPVlkG2AxIPKn9RIoQrUYHvcPRPEGzksYtAYyRF5yyB6laE+MsGWQrf9t6fOpf7eI1YIEv9uI" +
            "B5HB+bMULe+GPYMCmbz88/23d18W2QZ4btPgHXmg7c76tCJTROvWGQQAEbgThCVBziJYnOK4Y7EkgxxHQfW4hv/+vw9gIiGJUs/kaiFeBEMPbt/O5D1kUad7" +
            "i0oGeLzUAQpFgGi7gS2HmskU+cshUgW8ZncCAEgaAu/dsi5nppcMsJVj+Nij2/D+vQncygExrSKv/Pp7nLuC/TNE9QZG6XA/48ChhZIs2svK15N2hASMYGpM" +
            "YCQh9T8higCRV/awZPCSRUHnCighgWnSsCSgRBrS++4ZAVIUtJgrS5//9h8XcX21BH7ibRb9yFDlS0hjQ8NyBSCuAw8crciidhfRbAO8aVQaYC7UDtAUCUiL" +
            "B2J5nSlrlgR5i+C2SY47FwDzwmXocQ2/9MQBJI3qQ/f1pE6rV2abWIxLIsxOAh+/31FEy5OOVoAHZRie3o0nJSXAOncBr4oicQDbefDdfQjeTpEKkOoeFY+v" +
            "95sdZJPAnjhh6gI//aiGv8IE7rp7CUe3JXAzB8Ro7fQJNwmcj4+gi9MCZhi5H6M8X4nJ6QxHdgK75oBnX2R48RyVsigmWjaQ2L4FZm4TGcgz/+TdlRXRxeJi" +
            "FcjDTJ3gPpdmCMfUCeF4DL+RK/XGrsiJEQSmLjAzngYvAiv5+tMnWMh/ZxxI6MDTq8Czy1LSNL07aEDcAN6+Bfzf4wKXrxBAE/JaqBZcHm6YBJ98BEjHS6fA" +
            "aJ9PVe6VswyNNNXREJ2nxEGJeq0TQZXjvEVwfVV2cdarHdSTQ24T3A7TyhiQyck+nR95kOCj97UwW1QywOl4xQAPQgGsqxIorI73unVE1JFDxFGepC45REs7" +
            "gVsOlW+fp153EYtSj5D3JX1uEtijGv12AFt+tWKMoKZJ4GsOaW6VUrMH5oEdm4Cnn29OFmlUPsemUY4Y1ZCVh+YgyOBIoZ7aydwNVl6ZojDZIecxS2d2yOuY" +
            "pZeG95pkXVtR9k7V+jXbOTNA9v8ZtBrAUVd99/faz5srAGYMeOgeDZ/4MMHiDl7OFjWyG2yb1arM76DIn548zklakB0iIX9hv5ZqN+C9/j2IDPW6VZtd+Z1/" +
            "+j0X51IWbR4Hfuj9tFoWhSSCbYBnJhxZvAFLg+q9+EM5JRHxuHghTHbILYdsY0wIoDnMsdvYOmWRH3j9sjyMh5M3NTtMSc6EkTxe4K8huePxC6V2i/csymzR" +
            "V4+Hk0X2If7FBY4t4wQ5CwMZei//cKTOzDi//7MBWHVnBRGAkCMY/XyBO0tUz7SGTXs6CeEkab2VPczqH+XSEOpoqYjpwMP3arhjP/D1kxxnz9Nytqhm9S8Q" +
            "GCPA9x6jiFEgxwerAjwQd4SRCIfuq5vzajNE9R4nqH3BNwNEvX1Kq25U92wlp/6/A+fAegHYvBH4lw9KWWTPLXJ/jE8K/OsPA1vHZXdqeScmagfoWtGs3vG7" +
            "mgs6aj4XEvaCgJDqDJFbEnllddwk8LpXwWtnqNlBuNdB/mg3q/gR1Q1+LzLoFMiXBgHfvhPYs53iO28CF64wrOYJRkyB3ds1LM0RJHSUMz8DsWL2IwG8Ksdh" +
            "/IDX57YvEILUgM5NBPchG7cniEoI5/frFEjGAGQkEDlv8u40Gm2bt78+VwBMHbhrF3DXLg0FXql053gt+JUH6EOv4EcCu2pc2VlEeWK1kwg6rQ9mNyH8btwp" +
            "f3+rb8yk9f+dBnwv4ygf6NcosGZVfh86BHcd6P0I8qAimVtOeJKgRAS7hwgg4I4HtXcEL4lSr9gVZWewzacbxEFDZwNXfBru3zXqT2QyJDfe6IOy0oeRQ25i" +
            "VGWJSunSiv6XnUoG4eV11GtFDKr+Oglh8epCmA1KN+Cbub3e+b2t1uuDVAEeVE/T5FQL5/iUCp1owCXhYfuCvLJJtE1yiDaw+pOAY6xU7QC9nxVy7wJecsjd" +
            "SVr1fjtIoJFw+XYeor/HeQ1sTcWYhpc/obI/DYB/GFf/vjfBYUhQ7xK9urIoZL++ZxuCqH18rwpzlDx+o9t52McjGC7tP/ASiISQKojQJq3RxtuKa5rvqIso" +
            "LXgjwoJfU6K3/3eAoIxQvTbroN0gqCDlByDnDuFnlstnCpzEcNYBfC8ej75aRQE/GeKVUh8GlntJonpAbyT/7dUHVO8xk7Hw8oS2oC4QBvx+8mdQ9f/AEDuM" +
            "biURZVGzL1I9yUQ8TDBtwTvRzGMMm/bv+x2AhDhBFrTieWWL3BLJ1EVVUczr7DIPuTPY3aFuP1A+mE/97yduFOj1ND9RufI+zwJ5kCDK3HriQ4Ro17tWk8PZ" +
            "S+SXTSo/T1gJRJu71lRlfQbYAxA0PxfK7776RoZA2ecMgnYG53OXR53w1t7n24pTd4Os/wfKBPvtBlFuMCE+PTt+O4IfQZw7g1sm1QNnkJFuBvRRTO8wtQro" +
            "g5jzR5NEaPRAihch3DKJidaDupV3PA/T6j9UvUBhhvO244RWv2bNBm36w1DUAUiIyXPuN98+KB/ltFmzuwIayF514vaeYQP/wBfCSITV0AZiq3cJvyOWQanZ" +
            "bgC/qh9QnQcYTCKIFjfeRSVD4I5QZ45qu4E/bKv/0BAgSCZVjkrWB59vq4BojiRhZBnpQFV32MA/lAQIHqpb5wyCmzAeY96jZp08j3cS78JelM+jAh+qEKYi" +
            "yk033c7cBH2u0oGKAF0z2sJH1vRiqpSQofG7nvH/AW0gnpgK52rXAAAAAElFTkSuQmCC"

    }
}