package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * 腾讯地图**原生启动图标**（从 {@code com.tencent.map} 11.7.0 的
 * {@code res/drawable-xxxhdpi-v4/default_home_icon.webp} 原图提取，512x512 缩放至 192x192 PNG）。
 *
 * <h3>为什么内嵌而不是读 APK</h3>
 * 已安装的 App 由 {@code MainActivity.appIcon()} 直接 {@code loadIcon()} 取真图标；
 * 但**未安装**时设备上没有任何图标可取。为了「未安装」状态也能显示原生图标（而不是
 * 手绘占位或下载箭头），这里把原图 base64 内嵌。装了 App 时仍走 loadIcon，本类不参与。
 *
 * <p>生成方式见 {@code .agent_work/scripts/mak_embed_tmap_icon.py}（幂等，可重跑）。
 */
class TencentIconAsset private constructor() {

    companion object {
        @Volatile
        private var cached: Bitmap? = null

        @Volatile
        private var failed = false

        @JvmStatic
        fun bitmap(): Bitmap? {
            if (cached != null || failed) return cached
            synchronized(TencentIconAsset::class.java) {
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

        private const val B64 = "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAABdgElEQVR42u29eZBk13Xe+Tv3vczKrLX3DejGQhIEwA1cRFEU" +
            "SRFcRJAUAUoktVCiqTBlW7tmbE04JiYcdtjjGc/EjB22R7ZGHjtkS2ONZVsWJFIESRCAKO479n3rRu/d1bVXZeZ798wf9y33" +
            "vXy5VlWjm+iKaFR3oSqrKvPcc7/zne98R9iGN1UVIABiEdH040tLuqfZ5DUqvA7llsBwjVV7BMxuESYEO2GMkb6PbbOHwyqs" +
            "dob/uURgqgb+N5D+3+7K25YEBO5JV7CKtUpH1S6LcM6IORFbjiF8XzS6f309fHB2Vs4NiqWtepPtCHwRidKPbWzoKwl5fyi8" +
            "JzDcAly1ye/hntDkAKyMeACmrxyAixPwgKKICKj7expy0ucpjy3Hge9HcXw3NvhsoyGPe699uNUHQbYw8I2IxAALC7qzOc1P" +
            "BcLPC7zdGMLS06NeXpBRfpbyDXDlAFyawZ8egMINgCQfUVRFVS0CihhFrSBGApO/PFFMBParEpg/WIH/ukPkQhJvAWC34iDI" +
            "FgR/kAb+yooeqNfjv2mC4FOB4Yj3aVHyvcxmv+eWHgABcf+58ra1SCBBPFI4E+LuhC5YpH5mdKfGAmoMYSDZYTiqNv637Xbw" +
            "e9PTcqocexf9ACRZX0TEHj16tHng0OHfCIS/Ywz70tsseW+2Empt+Q0gVw7AlsMeP9O7HI/3riv4RcBDtl6MZYeBWkAAEMec" +
            "sTb+Z7Va8M9FZF1VDaDj3gYyZvAbEbEAGx39YGj4J4Hh1Wm2t5bAmG0KqxRP6tYcACQ5BFfetiX7l1CRH9yMErHWWhUxcS1w" +
            "cDqyPBxb/m6jJp8px+Qob2ZMyGNPqk61Yv2diZBPB4ZXW0uc/K7htgV/qQjekiLoSvBvcfBLAdJ0ZVl1wU+pABwYqMaIoGE7" +
            "stqObBwaXjUR8umNTvyvT6pOiYhNaoPtOwD3qoYiErda+qp9lr+qG341uaKsMQRcbmDiSuxvAwRy2V/9TO//o4IJGfJ0gQhG" +
            "RIxI0I6sbXesnQjNL++Fv1pu6atFJE6Yoq0PAXXBH3U6ersx/Htj2JEUt+HFzjJbQoNKjkWv3AJb8JokgS8DII8wxgWu2vVC" +
            "uu8pqBLVQ8JOzGJH+WtTNfmzNFa37AZIH7AV6adMyJ8mwR9f7ODvCSg38Thy5RrYmotU+9wKJaZn6ED3Mn/+ta5izvKXEG50" +
            "iI1hbsLwp+st/ZSIRPcOeROEwwZ/O9LfqAX8i5SiwnXnLvtXTrO8deVtM4QEUhHgPtMzTO5Kb+KsSCj+Wzxa1adYRQg6EdYI" +
            "NOr8P+vr0WRT5F8OcxOYYTN/Evyxx+df/rhdr9wAYwe+atbochBSupP5sJm/6iCk3KjjODOopUnKyh4/PyvGWqQdYRuN4F+s" +
            "rLubYFBNYAawPdHGRucn6gG/mwS/edFLR71SCF8Kz7+IFJKHz/GrFgvdMQsL71rR7FBkfYWKxxaDxIq0Y+JGnX+zuNH5UHII" +
            "gpEOQMKpxksbelNYC/8wgUpyJVxewkGvebGbtri0lN/LDLWOUtz6j61px1hd7HuPPYgFN4JYi6hCMzR/cH659aqEHTJDHYCk" +
            "w4uqNidr/FFgmPOy/w8WdSnbcKP8QMZ/jsFzfU8xH2opK+uwUKfiQIj/0ohgE8ijQ75WIpjIYmuBmZts1P9fVW36sT3oBjAi" +
            "YtsR/2dgeB2O6gwuRbHVFQh0cTK/iAt8Vc3+7mMdzXQ+I75E5YhW/2PqV9ojv+wiBOttokbI61Za/NOkS2z6vvypuGh9vfO+" +
            "RiO865LB/f6bBZWtk0Jc0QKNoOpMmZiU/ZE8iGKV4SBPmekpcPu2yPRo+vExOtLu6xSsrddMsL7BbdNN+VxZQGdK0EdVdaJW" +
            "D/95hVz50nhdRK/AlhcJ/mT/9puHOiBAU+ySfpK17k/hoKn7mNc3sCo52zNOU87RRJK2DExg/4WqNpIYlyoIZETEdmJ+MzC8" +
            "MuH7zaWcma68bR/s8ZWdZaq4qPPPb4KC/iENdP8AlIveElcqhTpAR0Kqqloo0r0b3my0rW3WzA3rnfi3ylAoLXhN8uPvtpbH" +
            "jGHnuGK5bX99rsiht31sMZ3kysVtOjAJWdWRs3XlQ3oKOr9m1WxuI6dHc6iTv+9xOGwtNESxvdCpmRtn4Hwq5Td5HIjGMb9m" +
            "DLsv2ex/pWi9SE+xFG6BzZP6FXRq2kyzSea2yZ/0ZrCKJKKDrAGWHrISZeq/76H4NZ0I26iZ3WGHX0tmB5wkTFVFRFRV52LL" +
            "Y4Fhf/KjXpoH4EoRvI2itu48o0NSMOUbQK1uCc2sCHYLxIrJLSCdyJ5u1s2NIrKoqmJIKM4YPhIYDlzS2f9KEbw9eF+9YjTB" +
            "5WnGHe651jy7Ww+LFzTRNv9jY+/v+ccLjbbsWytGLcbND29m7sO0I2yzbg5sxHw0+XBg0pEzLJ94yZWYVzJ/F/RJXRyGD3wX" +
            "xGngS8bolIO9VAgXimD3R5LPV2uLkCelI9UiY4aowzzWXU4xn0iYICsAGxt6Q63GQ8ZQuyy051dsUbaGSZMijh46tspKNFXX" +
            "B7AVJ0dLjS2bHJpM9Gbc/zHdBiHpYI0kgrgMlqViuRF/XVVIbKc67ZhXzzXkCePGzeL3J8EfXfJZSmXLFaEv1QZXYSZChwh6" +
            "9bO7/+80qLVEg8agyQ0QR9DpgDHQmITJKZhous+PI4jjPPsnB0TQvAxPvp92jZkNCPoM6rkDFsdEjZCagQ9CMg8QBMF7LxdQ" +
            "sKU1gLxUZ3Y1lzgMwvkV2b4rsyt5I6vM9dvkYDQmoR4i8+eRp5+FjTWYmsFe9XJ0egZWVxFRREwusxMhx+iSB/+g+QFfS9Td" +
            "oRaAQHgP8M/CxUXdjeENXKK8fxVO3bJDoO5A/cDPBGiPYaB+WVQ9qFIV+Nn75Aawpa+LYwgCl+mtIs8/hvn6XZgHvwIXzkHU" +
            "gbBGsO9q4rffQXTrT2GjyB0YMRiJUTFO4+wdiK7DJd2+aj0CP30zkUKsvF5Vd0mno7eGIV+8bHLiFRp0LA1PQc3ZN+PbHh3b" +
            "ihvBrwEsaJwEcH0CGk1kbRke/ibBVz+DPPl92GjDzE73J6jB2hLML8DaAvaN7yX+lb9HjKI2f13SQ5CbmHmBLuLNcwspOnaX" +
            "mvTNe0YscWzeFapwSxL49iUJgX7Ak39Bw9Oz5uknVShTmd0QR23soqcxiYR1OHMMc889mG/fDS88A6YGO/bBoZ0Q1mB5BRZX" +
            "YXoGrt8Hx08gn/8TonqI/Po/hKUlNKgljV8LoqgJ8qlgkUyFkWC5AuzRAS+qWqvNhjErNr4ltNbe4tjQlyC7rj+4B8DP/ppJ" +
            "inUwzFGt4Pet9/ekGE2LXBOizRmIY+Tphwi+cRfy4Fdh4QJMzsD+612gxwpnzsLRF+DCgru+BTh8CF75SqTThrv+CH7sg8iN" +
            "b0LXV9Eg9GzjtMAEScXgGEOK50SMI54IbgknaubIZVUS6hYPslzuh0CLeF4S1wSXFbUa8owJc1BF4ziHOZOz6PIF5Jufx3z9" +
            "c8gT34dOBLO74ciN0JyEtXV48ll44bj7e1CDWj3/Xs8chSCEa64heuYB+PJnqL/2LeiqBZP0ZEUQtagEmejNGJMdc0nwfqoJ" +
            "GqwWVbEYrNrrwshyIDTgSo/LpHmlV5ph5ecjtyFPRZpaDXP8ALdaTXV2Bb9Fo9h9r4lJCEM48RzyrXsw37kPTj0LYQN2HISp" +
            "WRfg8/Pw6FMu63ciqNfdgSgftslJOHUarjrERrOJefJRJtodp8VRHCUaBPnXJQFuVTHJjWDTlsKQUmlrVSKnRdofouy5LOif" +
            "7aQGL0djrELmF0QVrbrQyjRmqsmvhDm+fp8E28cuQ0/PQqeDPHk/fPXzyMNfheVFmNoBB14Ok9Mu0E+edtn+wqJLqbUaNEKP" +
            "Eq0UEmE3WogoC2stJizUbIw1gYvshFESG6NBkHkDuSF5kwvk0lpAul9bn/ESEeIYVGR3KMLM5ZQLRbeQBr0cXeG0BIClCM9l" +
            "WJhTVdj6MEct1CbQyWlkeRH58l3w9c8jzzwKNoKZXXD4IExMwOoaPPYEnDjpmJ4whIlG7+9b/hnqdVpW0bNnmX/lbewPwqTG" +
            "sK5xJh6ss5p0jZOgFi30CERKlGjF91YM1kJgzGQo2PrllP+3lAXSyxMGFV5UzbO4DFPY9oQ56mhMdWwOQQinjmG+fQ/yzXvh" +
            "9HGH+3fshelp96SdOw/HjsP5eYitgz6NZveN04+XjiKYnaW1ssDS/Cqtt36AybjFhiZhrRYkyA5AetNRCPjet3r+d0VV0oaw" +
            "WAVR2wwH7eS68nYJNwSHKVz9k2Jt1/9Tm8gUTIBOziBxjDz9iIM5D3zdcfXNWTh4xAV3qw3HTsALJ2Bp2WXosOY0BeXDN4ym" +
            "yBhaO3Zin/4u5657Ewd/5K3I8gXEhN2/R14YJPAHTy1E15BM7lmq3kCNZ7JoREIu48mll1QRXNLqaxVHn9KdnrRZMhaodDji" +
            "2GH8sIZOzyGry8g37sV89fPw9EPusEzPwaFrXYCvrMBzT7qCdX3DYfuJifHM/v3sPzfHhsS0Thxn7VN/h6vnGrTml5B64HoM" +
            "gclgTtK6d+08TwYhfaVMUiqM/LU0Sni5sh4vVQZIe2V1LbvRelIFH9/b2OHoiQbUJuDcKcw9f45844tw+piDMTO7oN5wUyLn" +
            "L8DJUx7MqUGz2V1jjDmBE+/Zgz31PCemrmLn+z5EfXWBdRMkoDyJXKsllK6FgRktdfYz2CPFuki850iTWuHKDXA5Bb36gd/d" +
            "mU31+KJOTmBRAvKmlSqOijQB5uhTyFc/D9/9CixfgOY07NrnHrvVcvTl6bOwstoNc6zd/C8Vx9BssDYR0nn+CRbf/SvcctV+" +
            "2kunMfUJ97uZsLetilfsSyqp7nqukkOQsmRJ0Iu65wi5HA/AS6hnXTlZVcbyaQGbTnJpPsklah2jYwxMziBRGx75DvLlzyOP" +
            "fx86bWjOwOw+6KzBwjm4sAzzi9DqOP59MzCnH/yxMXbvPqKFs5zv1Gm+76Ps6CywLoZAq24yrZwBUH/Rg3e1W0kzPqWlpMlB" +
            "UNdDeGlDoEuNBSpQnOWMb3vqc/LJKZfZxMYQWzSsoTNzsLKE+doXkK98AZ5/0gVSYwZkAlaWHY25vAzL6+5mCQLXuBrFj5CR" +
            "Fn5BWKc1O4P93nc4/Yb38orX3IRdOQO1yYIzdFWrvgB5UMrDkuptQBH1hHPa/QiX3QHY0j7AJVoHqFYUrj59iae/90cKE5mC" +
            "1ifQ5gScO01w36fha/ci505CYwomd8DaIpw57gJ/bQNaCf0ZGAhk+wI/K347sHcvG+uLrMwvIT/+M+wP2rRVur5vOd8N+2Ol" +
            "kEdc3ey9L2Loy+4AbPVQ/CXRCfadwK12N6l8AykP9+d2IrF7wRtN1ITwwjOYr92N+e5XYXnJidKm52BlEebPucDvWOgk6x78" +
            "ZtN2BX7Jx7y9cw969AFOHrqZA296E8HqIpgg72VkWqaSN1ApaWmXgEf6HAbpgk/hSxr/Xwqd4FTSYLUIdzLJQinwk0aQ2GTo" +
            "BNDmpDvITz9G8OXPIQ99F9otmJqBmVlYXoAL87CyBhud/Dk0ZvTUutnbNopgZpb1IKZ9/BirP/33eM2OKVpL5zD1IJlS7x3M" +
            "WSHbJxRSqJNDHh/+FBd3X6kBXmQYpL7xvQ95skK3wlszdtNSOjmNdjrIg98m+KvPIk88DGpc4NdreeAvrTqdTpzICOTF3Ell" +
            "iXfvJj5zlFMT+9j14x9icmOR9SDEZOdQqjKVN9QjIyCGEv2ZzVUJwWV5AH5ArFEKs7gFuFPtp5l1bIMQnZpB11Yx37iP4Euf" +
            "g+efgrAOU3sgbsPyvBs7XFpznVsVF/gvdtPfxtCYZH2ygX3waRZ++OO85shBWotnkHrD0ZUmZWpM92skUljGkW6MEfUaY6Wr" +
            "VVS7GCO9rCHQ5R78/mK5roC3lW7KGkVZx5alC8jX78N8+fPIiWNQn4S5AxC1YPGsozIXlhyNKcbBHHMJUMci0InQgwdpL51m" +
            "ft1S//GfZEdnlXUJCYWEzZEkmCUZhZSSj5e7DbJP8XaIDcLJ6tcUepkWwVv+Ql7EgZgC3OkV/JkwLXZQJ6yh07PIhXPIvZ9G" +
            "vn4fcuYUNGZh51VgO7B0Fs6chMUliKzL9GHYvbblYg5up21Yf5lGIKzPTKMPP8jJG9/O9be8Cru2gAkn8myeGR9IYUuMlj2D" +
            "0gWHpvoFTRkfzeZ9K4bkL7tG2OU6Debv11JNvHK84tZ6cgUbu+GTeh2dmoHTJzCf/2+Yr94HC4swOQd7rgG7AUsn4dQpF/ix" +
            "BRNAGFwENkerxXaVLroJo9PpwK69tNqrrJ89j37iZ9kXWNoqCTJLM3uq2HSHQMmJioKgLfn/1a+h4/99vK89rv/wsuqK6tbH" +
            "fNY13SZ8rL5XTmoqZdVlSa+jm2Z86hPozDScOo75yn9GvvVlOL8EMztg39XABpw/BmdO5bO1YZhnfNVtunL9x7alTKQV/y7T" +
            "Lpb2nt3Yow/xwr4b2ffDbyVYXaIThE7uk8AdF/DeUqLk79lhKL56KIZqACTYQS7A2u8GKHcl5SJn2tL3L8w5aPWOtWEe05SF" +
            "gdtstSx+lk87t749YOqIFtbR5hRy6gXkS1/EfPPrjsOf2QmHrwFdhXNH4fhxh/HBafa3rHFli4K6QhBLd1bvdtbt/aTbGOZm" +
            "WDPQeeE4qz/7D3jNniadhQ2Cepr1jUtCxjiBW2p5kizJizX/d05iejKI7lE4TMEGRrsCSIBwvaOXWcWa3wQ6chMNNqLyQ8oW" +
            "FhdS6t6mN0yytzM1iwUHWeIIrdehMUlw+ji1L92N+fbXHV8/MwtHjgAdOPssPPc8LK24AAhrMKRFYG/oUg5w26fYsn32P2qP" +
            "A+LBH9sh3rUPe/ZZzk3sZObH3k+wvMyaBgSxG2skXY2qjslNDfo1yVhqEtxv053BPvOjfZwfCxs38syp6jR+7ehy0ZZpJXsj" +
            "Iz5ESwdsKtxMb0J9TX6S+RLloXhW4BLHaK2GTs1izpyg8aW7qX37a8jqGuzYCdfsBduG08/D80/D4rLT59RqQ/zMZfjRC5v7" +
            "b3EfKDPAWLTn53iwr9lktTFBfP/TnPzhT3DT4YOszJ/CTkwSxSnrY9BYvPwmibub+KMADvakcFU8abNW2aJUGqFmz0lglFDk" +
            "Jc58ylZulkhEaYk0QQqZ3yJR5Fid2SnMuVNM3PVfqX/za7C6Cjt3wYGDEHfg9FF45glYWEjmayeqnZb7ZvVRArec7RnwOYMe" +
            "w8v+UQvdfTXR4lmW2sLEbT/DXGeFtaDmnJoDR9Vq4HoAaoyrAYxk2+ELeDf7ePdFrsNCbe8hrzTCtiL4s2ZVqstJAz+5BZKR" +
            "w3hmFlk4z8S9n2bia19CFpdg1y44eBAChWPPwdOPucAPDDQaSbEcVxj32f6Zd1BmHinDS5+v7/N4qhAGrE/PoQ/+FcdveBsH" +
            "b76ReO0cJmx6Wh6P85e8YaepVENMRoMqFV6gKmOjmCsHYAsCPxtOsbaY9aPIMRtTM8j6Ms0v/jm1L9+HOX/OZfybbnT7eY4/" +
            "D4884Lq3YQgTteTx2hX8+rAB3yvAh/lYrxpgADwqOzd3WrDzIOutZVYvrNH65CfYF8a0NC94CwGfdbeK2b+X9GHkxdxXDgBb" +
            "p2f3vTETuGNSDU8cgVrs5DQStZn42heZ+MsvIKdOw8wM3HgDTDXh+DPwwHfgXBL4zYnkMSO6xUp2QGDLgFuhX7E65A2hsafV" +
            "lx43gHoiHEt7z144+gBnD7yC/W95m1OkBmGe/RMGKF2WkS7KUI/tKUghPDOsNPtz5QC8GHAnKWit22GFqqM04whtNCAw1B75" +
            "Lo27P0PwzNPOJvwVL4dds3DqGHzlLjh13AV+YyKhC6Mh4MkA1qUvrLGDbwctf48SFaq2zw3iFZpRB2Z2sBZY2i/Mc/6nf4M3" +
            "zE3Qnl9CJoJMppEdgjTQfUhUZuqSxpdNbopx+Qt/uOzKARgr67tuboHd6Ti/ezszS/j8kzTv/nOChx9y3dnrXwaH9sP5F+Du" +
            "u+Ho88mYYjpcHg2J28uZflA2H5K5qSyu7RC3Q69/G9AOnT17iE48xdnpHcy96w4m1hZZC2sYkcTtIYFBJMUwphv+ZAchvxU2" +
            "LQbwVBrhSy+JCyI6JtbPqcwi3AE7M4NZOM/kZ/8z9a//lZMfHzwE1xyG1iJ86TPw+P3uRW4kq4FsNCCo+kEcrYBJOkSQD6of" +
            "dIhDpH105eoUqY0GG41J9IUTnH/rL3LDkf20L5zGTDS9AHfv1Zgc/qT/7vKvl0wB6i4LHUkWXWiFeR6qL7kDIKLDH4IurO8g" +
            "j2jikBxFaHMSsEx89R4mvvBpzPw87N4L1x5G64J89z544JvuhmhMJo8XVQSU9DkAtj9bo/2+flhOX8f4moqOsQC2hd17Ne0L" +
            "p5lvhwS3/QKznRXWTIhJsb6YxOfTH8yR4rRvV/aXrL4Y6waooEnDl9xaXC0+iabqIJSzfoLxTfr3qAMSoHNzBE8/RuMz/4Xw" +
            "ySfcIMqrXgU7Z9An7ke+dR8sLjr832wmdOYwGXaYxlY/yDJMlh/he45yINRpk1Ynd6D3f4sTr3sf19x0E9HKaaTW8OjN5LAY" +
            "UyhwczqUkuyhf4oYei9gSUUQ6mXuMiIjQJ/CRFAiVbDJnWhSfOnP3yZ+OunHNI4h6qBT08jGKs3/9gfUv/yXriY4cgSuOgBn" +
            "jsKf/Cfk5FFoTjkfTRvn443DcOs6TObtU4T2/fgwt4AOQTpWRI6IG8XcexXtjTVWl2K47ZPsMi1amEzzo+l61MSoU1PRm5fp" +
            "NbPGSmXSkrxWMpj61P5UgQ+Dwrq5vDN6xw5tJwEq1I0W12dma3fU4fqsVW5B3JIGUU2yvqA75zAPf4/6n/5/mJMnYdceOHQQ" +
            "aMGX/gwe/Z4bR5ydSw5Qpwdn3tcHZUA9MCzrMwjfj1tE95rojyEQNnbtR556mONHXsuhN/4Q9bUFtF5HQnGwR4Ca4/018KbV" +
            "jKAmwffGMamavnaS9gm0wiki/biMdEMYgTANiMv1AERWhlhxmxuphqboLm6z5RKWQJyI1vlpxsmhiZ08oTmJdNqY//rvkS/d" +
            "B+EEXHsdzE3BU/fD/V+C9TWYms4LwbKD80gBOSi79zoYvYrhAbz94Gex4jESG/UUq0cx7NjLWgxrp9bZ+Bu/yJEZwS5BPUws" +
            "V4JkGUVoUJNk/MDk9a5JMH92AMSH/n1vMvVUnv6usF50qTEJBPpB2v7ZRXxIjzBTTUJdklrXHQJBMQnFmXH7szuQZ5/G/NG/" +
            "Q44ehT37YP8uWD4DX/hzOPms89xpTlY0sQY1jaRCY2+HXQE9ABXrGIFe9T1sheaotHIpIanau65GX3iSM3uPsPfd76e+usBa" +
            "UCMwgpUgUTYETv+fFbr++GOq/femwFIhXA93uOojKkP1xUMjhT073Z8l5U1k6g1j9lMfVXxer8cq/V26/JG8JQi+6GyYl1Cl" +
            "wlPfUWhacg2LRRALVg2hjRxTNDuH+csvYP7kj50VyZEjMFOHR78GD33LZcGp6QTuREOwJeUDIZuAKv0+d8iMrnGSwa23cCOu" +
            "VKH3nfeNLczMsF6v0zmxyPwdv8qbd03TWlwnqBtX+BpBgiArgtWYYuGbNcbc+1T0aZFsMySVqv5uwUSBJhUqRmmSi0jKi4al" +
            "xy9YqUWW4cpTkcGPVfrGUuEP0/WrqvYOfo8qS30zfWWho0LzNVk2M1I2BDaiE9QIDNT/47/D/OU9MLsD9u6Ezjzce4/r4jab" +
            "ILUE549SWGopu44Kcewo96CXva33JMYFl4VNDzNZiPdeTefMUc7Vppl7zx1MtRK7E6/j2yV98EVwaV+gUMBKYeg9+5GzZN0r" +
            "BnIdkXhOEIJi06R4uapBpTTf0MM9KQl+m2cRqxnzkGnYUkYo6Tpaa4lMyOTGIsEf/BvMgw/AvgMwNwHHH4DvfxXiFkxNJnp/" +
            "O2BgeRA2H6bYtCOAQZt3Y1OMjtKV/qo4wWE1deUXw1qYaLJcn0OffZQzb/9FXvuKq2gvnEXq9UT0lrBAJpFApFSoJ3tOG2Dp" +
            "WUgtgkw2Kun9KL4kWrt1DpJtzVTPQ9Rkxa9etsZY/gJo6Y9+NSuOJKH2c1xptRSO6g5JHEM4G3L+j/4LV3/3G8TX3UQQrsEj" +
            "98FT33fFb73uKTWrgm8Ujp/R2W2NPThZNYTuOULJGCXASAZlApGi+w8SL59joRMy8b5fYEdnjXUTEpggz/gJ/FE8zr/wd4pW" +
            "KMZkA/GF27/wuktv1JI+jtXKbTIgyUSx5Kvpy/8uv0//XvXx8p9Bn1N+zHTBQeXn9LzkqidVc6zvGaxJBTBRdyUqQtvCjobh" +
            "L55Rfu7Az/HATW8lWH+S6Hufg8e/A/Vmkj6iJNjTP3Hyx3oFo/939T4n7vH5VYA7fZzIjUZq270nSv7Epe+hXXvDKgNetnJW" +
            "XqEesja3B557lhOvupUjN9+AXV/F1MIMz4sx2eRXBn2S2wDJ83QatGpMz3uU0nupCvwy7BZvkEZ8KrQUzFXBXf7/VQFe9WfQ" +
            "53QdJtP75ygfhK4SUpMRuqzotQnslTw3p16y6mBPrDkebFvYVYe7zwj/6gnYv2uWP/zwb/CVM23Cpx8jmpkF7SQFoh/41gvw" +
            "9GNRRbD3u6uSr9UoOVyd5HslAa8elMk2vlwC3kqSZP/dh1hfW+HCYgwf/lscDNu0CBARrHHBn8Ie9ecAyt1fryucvealQE8W" +
            "o2ISkGeSf0upJO5yVkQL3zJRXV9G6yHL6y89g9Su293D/UgRmFiPEUoPS0dhKoSHFuF/exT2TQpvmrJ89qkG73nPH3LvK95D" +
            "uHieKAgqMrgf4LYC8qQB2yn+IXIBrm0v8KPSY42RweUiuuSpgglYm92PPPs0Z17+eg7/0BvQ1WUkDAtBXsz+UhS59SVKet/7" +
            "aXFrvbSu/Q5rl5GAXF77sQvPUWJ011vz420SKvjPagEwRAqhwHIE//hh9/fX74Q7jxseOmNpBTPc/tE/5Z4j7yJcXiQKjPfV" +
            "tuKSTm+HjpfN23lnOf3/aaOtKi3rJjK4jm+2MU72Z8ceWjZi/WyL1m2/zKGG0LKCMYJK4OTgqQTCmALbU5z3lW7s3tPgMH+v" +
            "I3Z/UzsVnyq4TJtgkjG7WqA8LanoTSU3vrIKcfKLWy3+aQbwzx6Hkxvwxt3wtdPw9dMQNAwmsqzYKe746T/j3iPvIlxeIQrC" +
            "JIiTDJ5mc/wsb7u9dpQXf9WTbNXP4Ars1p5DcOwpThy4nv1vv5VwbckN+UhQ8PbJg91UMj+Uh98HEU95z37w5eWxXVaLWdJc" +
            "pprmZC9W8UpUtckv6e4HG2tirOQ+lurMrCpWHe7fUYM/Pgb3nYZbdsLxVbjrBQgmkgMihiC2rERT3P6xO7nnyLvdITDeM5k2" +
            "jrbKlU3YPmNf3aLnP1aY28UqIesvLLN029/g2t0TtCKb8P451lfx9P4ZKDd5/h3RmsQOwdwWXg/J4ZEUlmvIZXgAvCXIWog5" +
            "zXG/N0mUYkTH9qSHxhCrMBXCI0vw+8/A9TNQE7jzeZCwGMvZIYinvUPQSW6Cl+gSQAvtXYfR489xauc+9r73NhrrS2hQSwre" +
            "wBW/SZYvDLx3tWsG4//sdezmfQYmSjRXDvivq1yWN0Cv7q/H6GhW6Iq7DbSE0JMnxCr8yyddojo8CZ8+BiuJMqCrFWXcIVi1" +
            "U9zx03dy7zXvIlzZxkNwCblaV8oeJmdZqTWxR8+y9M6/xrUH9rKx0Umwfx5WRbmDR39K0f1ByxtrKi88HQ7vl9wptGwi5P1v" +
            "c9kNtFSJnNQWGlsp3LFIUgQnbEES9G0r7KjBfzoKjyzCTbPw7fPwzJIzLOiFZNJDsBJ7cOjFPgQXu3YQV/LE+65Gzx3jzMQs" +
            "s7d9jNn2InGW/ZMGViKB0FKw+/O9+Iel4rW1BbwvI7MlfrPbH/W4LA+AaPeW8FwYJ5nlpqhiJac8M0t+nH3+ZKA8sqz88TG4" +
            "bhqW2vDlU2BqXpHU6+YXQxBZVqKLBIcuNeltZGFqktXJWewzxzn1oz/NDTceprPRwgQO+oDn+JApPfu2rxK4VO3yPBzLU14f" +
            "W71cs3yEzOUmApKSGM5azagw6wlaHfTRgu4/1lwF+HtPu8fYNQH3nHQM/FAJRsHiDsFqNMUdH/XgUBhefqtaZdQF17h533Mn" +
            "OB83mP7wJ5npLBGZWjLxlY86qvGpT1PM/pJn/6yNpdpfGNLravYUxqnkpas5XCVMuWyKYM1XiNqyLDrVj6eFjqZFbx74KWvQ" +
            "iWG2Jtx1Er5/AV4+Aw/OO+bHBEOQOP6aXuOxQx+5k3uu2cRNoBepmSVjwijxTAKaDVamdmKfPcqJt9zODa96GbqyjgQ5xhcf" +
            "43dRn6ab8kwK5FTyrBlXXxp2Kjez0kxfGnzJXibtv+T+8imCU08m7wnxBybUugJY0GyRgvoNryT71wyc3VD+8JhwsAmrEXzz" +
            "HJhwtODP8akh6FhW2tPc/lObOATDcPOyBYWxbqbgFof9dx+mfeEci62A+u1/jZ3xMpFxXV+bNL1IJBCKpzmgaGqrvr5fRlyC" +
            "UugtaM/LYZiHumxuAC39hv6wg6pm0CiuKHpT7D8VKP/xKCy2YXcDvnwaWvEQwdVr9NbmFOlqe4o7fvJO7r32XaMdAhniBhjF" +
            "CUq3qfBVhVqd1ald8MyznH7Ne7jh9a9FV9eQIMiUnpnkmbLLg5QYmWSzS9KnSWsEO6w2VrVn4SwyOKGpB4cvA/iTLEOQfEN6" +
            "elVam0tkU75f1df6u8H5yQAeXhbuPi0cnoLnluHoygjZn2qJTwEOtaa4/Sfv5J5rR2CHlMH7i/XFnjkV6Ch2z1VsrC2ysmLR" +
            "D/8y+4IOcVLwWkm2vJvykEsV5ZnfBHFyk6e9nb5NMf+GEKmUv4y2M+QygUBaOeiSNsMSs1RP76Ol58tN+in/+QWoJ2TDN88l" +
            "Ndg4wV/xMasJO7Qxze0fHoIilYuQzWULey9hwOrOfQTPPs3RV9/K9T/8JlhdcttqUirTpOOMSaHrQaLi1KHBH+zKGlXl4K+K" +
            "5KoyYNRxCu/2MJd+8Oerc2zm6SMVg4HiGKGS5DmyMB3C1+aFR5aEA014YB6W29UNr4H+ULb3dKM1hsBaVltTfPgn7uS+w32a" +
            "ZXqZ9Akkz/6t9RUWz3eIP/zLXNXoEFkpFLxiDNaUpM05z5kNu2dPW2kGXbs2AUmFkK3bhX0cMit94cwlvxky3RslVVN8Lo/E" +
            "IgXJs3rwR4C1SPnTE8KOOlxow2OLIMFom4YGjuqWCuPlaIo7Pngn9x7ucxPIJUiLVmX/IGBj5wHk6Sc5dtOP8op3vNXZnNdq" +
            "+byGyRtemup8xFQ3voQi/en/yjp8veTDHR0ynlSLYzXmUlc8lCktWzX0pClF6s1HqRKpMB3CPWeFExswU4Pvn3cqXpERg98O" +
            "/zErhjC2LEXTfLjfIdDtzNxb8DVJ9tc9h9jYWGX5bBv98C9zdSMiitPsbyAIkgPQjffL2F8rdf0Uu8WqlYFQCHhlFFVQgUTx" +
            "dzabS3khtt+69q8+d5IlUXU66OM/V67pJYQC59rKZ08Luyfg2Aq8MAznP0ygxxVQKPb/tyuMlzpT3PGBBA6tbmHHWLYB/mg1" +
            "9l/ffRCeeZIXXvFmrvuxd8DSYo79U5yPFPz+8xlfb1jF2/iiVRlItY8KKLcF8v2edMTgv+RpUC2J2VR6m5z6jg4+66MJ9p8M" +
            "XPAvdaARwMOLQ1CKOuRtwIDtRZ6UerkzxR233ck9B949/CHYigCXLRh33HOQtST7tz/yGxyZtEQxXuPL4/3LVideUUxhaqtP" +
            "9q/4Rb194tWrDMYM/kuzD6AU3Ju1wjfWat4xLN8QVpUYoW7ghXX4y7PC/gY8vwwXNpLsPyr0GeVzSjOalgQOxdPc8YE/6z4E" +
            "so0wSDfL/BjWdx1y2P/lb+blt74DWV7wsr+gieuDljn/soRdpFuX4Ad/lX4/kTVsZ0lkLj3KXystpQoZ3vvc7o8JsXUZ/zOn" +
            "nNNDLYBHFpLfdlTo04f/r9whXfH3OBXQ2UnueP+d3HfAg0O6BRBoUDEt4zE/7D7E+sYqK2fbtD/66xyejB32D4KKJpcvcy6+" +
            "L3Tt+1nRl7F+ydpyVAJFh2gImEsP+0u3dqOiuaGqha5hCn1ihYaBZ1bha+cd7fn0kuv+GjMg+w9T9PaqA/qZNvuTZXaKD40K" +
            "hxhy9e9WrVFMmJ+1XVdl2f9lt/4YsryEpoI/SZSfqezZmO4lvJl10JBugl23w+YDXwZMm10yB8DP4r2WuWuJCeqiwlLNjyif" +
            "PuU+Fgo8dGGIppeOAYWG8bjyKVJNtEPxNHe878/GPwSyzQMvHcXuPcTa+gqLZ9q0PvqbHGk67G9M4DJJEBTner1CuLLz6xXB" +
            "leZVdDe40NGmTKsy/mVRAxRZr6LXg9UKe9kK6GOtoz2bBh5fEb59QbhqEp5cgpVBTS8d4zYYdbm6Ld0E8SR3vK8Ehy4F2bQq" +
            "1Gqs7LoaeeoJnrvxR3lFhv3DzMxK/YBHSquOck9PfzqsMvtLvv+rVAKMnPlFRt8ZZi418r845O7TwJqMPVbcGJoL4QJxzE8g" +
            "zo7+oQXX9BrgoTj+gbBD7pXQiqGazhQfeu+d3LN/yJtgq+0Ne3Z9D9NeX2LxfAc+9hscaXZy7G9S6tOTOBvjnuQC+yOFzm91" +
            "8Et/g/FRpQ0jXBfpz2UuBc5Te4zEFaFOqZDSbHWXkzwoTBp4bEX4zgJcOwWPL8FKawzsP+jfo2wkstWPkQ7VrHSmueO9CRxa" +
            "e5Eny1ShXmd5x37ME4/z/M3v5IYfexsseZoff7jdszPPHB8KHv8mlz5XwZ+U6i7B2GHjeNhCtyr49UWHQOqyui1vDaqARVa7" +
            "Zzt9Z+fU/FZE+fNTieBN4P55l5isjln4DlqoMsjbdkBH2YrTDq1Ek9zxrju5b38Ch0y49TYpMmT2P3AN7YWzLFyICD7+t7kq" +
            "XCey/pCLKQ66Zwa2vuVJmfaUHh6uUsD5qhenx1Q0x30RBrhVi86eot0eb1a7V2oVXN68rY9xkv0fWRHuXxSunYInFmG1XZi5" +
            "Hp03t0MyL712Wwxxe2Q3QTTFh96VsEPrpUOgW7EuZ8DpSKa9lmd2I888xbNv+gle+cO3oCsrJeyfrzZ1xlemcuBd+1oVynDL" +
            "7rYw61dl/4tzA5Ts8d3wiqcLSUcYewS/td0Ct8xv2VvmiCifPSVMBq4H8L3zI2J/O0IDbJx9chUmzoWOsXWD9nfc+mf5IRgF" +
            "DslmZn2BCDr7r6dz7gTnWnUmP/Fb7DdrRJik6+sNt/uzviJd/P9Q3j26uYw/zCHwb5pejiLmYowzpgWs7+qoVDc6uvAgZUGc" +
            "ZnJnkmGXZgCPr8D9C3D9NDy8MKTceVwaVIfA/cMufCybb3UmuePHEnZorQIOjcpkyZA+P9OzLDXn4PGjvPC2X+Cm178au7qW" +
            "mNyaIvRJ3mewyIM6PvOj/v9PAtAWvJy3N/NLaXb84tOg6WYiz8ezfP2V8bn2WWRiPS9/XwkaivK500IjcHO/3z43IPsPu7ei" +
            "KvjtAFam3+EZsNfakh6CKT70jjv73wSyhXIIhfbB69EXnuKUmWHu5/4WO9oXiE2IpIxPEFR0e71Njxnt2Zv1yReZbn22r34Z" +
            "e7TCNTVH2041qKaZvrjCkpKyk37Zv2B2pZmrrwJWhEiFyUB5fAW+uyC8fAYevuCyf1/mx24BftYR64h+tYGW+gQpHHq7xw6Z" +
            "cJuGXSw6t5slUyN+7ixHP/BrvOqVV2HXNjBBmJnpq5SZH3+vV3HJdfXTYHrO8G71CHMZ9nSLKHPGxWznJFea+TWZaFFvoqeS" +
            "YNHuOiDT/3sO0Na6xpe1SiDwudNCzTj251vnBwy7DFJ79qsNBu2m6wV9dPj54gwOdSwr7UnueNud3Lv/XcXCWLYA92f+/oa1" +
            "Ay9Dnnucp3ddz6Gf/UUm184Rm1qyvNrn/Y3H/JiikC1liJAhdvlsf8GrQ/yf7WmE+Zt6/CGEAc9BWuxWBb9FS00vVwA3A3hq" +
            "Fb6zKFw/DY8twPIg3n9U5mfYzq+O0VPoxRTF+XjlSmeK2996J/fsfTfhRnIIdAuG5xO5s91/NWutDTaOLrH003+X1xxsELcs" +
            "xvP50dJkl5TkzpVNL5Gxg38zLE8/C8WqCTKzpeOLWlxhkFqWpD+QVPD9XVKIrmkwxVrpGnK3CjWjfPGs6/o2DHzzPDDKsMsw" +
            "2VmHhEF2COgzTAdaSwI665plt7/1Tu7Z865uinRcOGQt1CZY2nk1weMP89gNP8qNP3EbwdI8GtZLWT+BQcZ4Cy9Kiy6yAZiS" +
            "Zf02w56ye7T0CHr/NtHtMMeVxKRIy4Vuyai06iaoaoD5Lg/F9UYQW2XCwLOr8PV54bopeHIZFjYg2IzmZ5jiVbegoTbs5yhY" +
            "mwzax1Pc8SN3cu/eHodgFKCcmFy1r345nXMnmF8E86n/iWubbaI4oT0TwVuW/ZMDIb74TaSwdSU3wOpeYjL22OLQyhDptTG1" +
            "72J1sz0tNu29vld7rDMqQZxU758Ov8Ra9PhsBMpdp1wTbDp02H9g9pdtkhjbAesqh50o67FerLCf4Efu5J49Hhwa5wREFnbu" +
            "YbExgzz+HI/d+ilueest2KXlRPIQFJtbXTp/iqrPTASnYy3BKWZnHaOxJT2XZVm7XWrQwourlVedlkxcdBD8qRp8LxTCLvjr" +
            "Bo6tw5fOuez/9DKcXxsC+9sRIIwOKUIbRzZdNV9Mn9vHh0PZIXjXeIdAXem3cuB65OlHONbcz6FP/ho7N+aJTS3J/i7j+5le" +
            "k+yPv+yuiu4cA/vLGPLPfoGvWr5NtuMAiEd1qhRWU2vXGI9km1u6ZgDKtKeW9veWOsCxVZoB3H1aWIth5wR8e36Lpr1G6fqO" +
            "wiqNO3WmFexQbFntTHHHm+/k3t0jHoJE7xMfupa19TU2ji5x6uf/Aa+9bifxesft9U31/oXl1qXBF2MKzE8W9KWm16BDkJmb" +
            "jcDz50+v9pHW69DaIrOJqXVvuTEZzdkFd3Q4nVhZXVx+nw+7wNkW3HtWuHYajq3CqZUBs75bMqM8pOZ/0JzvMAfC9qZZreZ9" +
            "gtvfPAIc8jq+C3MHqD3yEA+87oPc/JMfJlw8h9bqnpw5zf7+4HvgFb50Lbcod3wHRd84+v38ZpEei1I0w/vDIikzlk151lrW" +
            "LO9rZSOstwrAasm+Wn3Io/lQvMf6RBaaIdx3BubbsHfCWRyObDM4bJbWIba19QvyYaTVw1CwpfRn8eDQD93JPcPcBOp0J6tX" +
            "34gefYLnoh1M/ur/zDXmAh0NHfQhCX6P5kyH3cXb7Ni15jS5nVTyLZw9d32NiffVK6e18jHHU5QaNqWVkTzdS69trgN6BVVN" +
            "r4qPK07ysNh2soerJ+HEmvP6GWhwq5v8d9Wh0B76HxnhewxqmvU5HGlNsBpNcccPDYBDxkGf6ODLWW6t037qHM9+/B/xllfv" +
            "JV7dQOpJ9k/ZH2+zO8mmdzVSXHInRRiUunXQx8Vh9AK3nE+kj3JARwv+cTrBmbShLPaTHo7CQ7yWWnKBsJ7pVXm10VQAX5kX" +
            "Tm7AoQZ8d36bJwXHmcAaMoCHkmNoCRJV7SeIEzj0pju5Z3cFHBJx+2B37eHC7B4aDzzI/a+/nTd+/GM0Fs6g9WayzysoZf8g" +
            "WVphcrvzMhtU8exrlXPziIPq3dKG3gqikQO/9OBm6Kkt1eJi8x7D62Vas78LhnbdAoUFGN6md0FZi+GuU8K+Bpxvu6V2fQde" +
            "hjW81SFhyLj6oWFHLPsdgurtcZ6Abprb31iCQ5Lo/OsNFg68AvP4gzxbv5rdf/t/4Rou0DETTtGZFL5F6OPdBlKCP4UiWAY+" +
            "JVJxIIZ/imWUZaGjjd9qvwPgXy1opvbzXXr7Yf6qUU+rVDg6eGtME+xfdojoWGUqgO9cgGdW4MiUm/ay6u1eHjdYdYQAHjTz" +
            "q5tgfrS3QG7Qx60YAk2aZW/w4JAEYIW1615D68wLrBxf4fhv/kve+PI5olaE1Gp58JeZnrQYTl/z5EagNPieryLdPMvTa0Fe" +
            "lTvIqI9bNQ3W/wZIWBzxKn9fXSelH8wyuMNbdn/w9/rmfQIP+nj2GLG6gZedddiI4dGFIbO/jngYdAzufxTqU0c4gMMI7qrg" +
            "0C0JHFrpsHz9LSytryEPH+P+j/9D3v7Bd2AWF9B6w2l6TEJzpp3fJPsX9nxVsT0Vc74ypqND9a8tlV1d1dG1H1q6Mvzq1PR6" +
            "kdLMrz2CQem/gGzURKsFiiuHRZFVJkO33eXBRbgmyf6deMjsP+rqo622IxzXckVHY53UQqz5IfjAq/+Ev3r1x5jaOEv4vYf5" +
            "xpt/llt+5VeYWz5NFDYwXsDnUMdXevo6IG8Bhuf3LwOQg4yZ9SsRRkZv6sivmSQwrFi2uu8RVjW2RKUwua/+FoLkKKY/hwzj" +
            "N2uLd0aa/a0HfVLJQ3nTS5zAnL846QZewJncjoz9NwOHRp0eG2eueFDh3eOgqC3uUY4xEMVce2iWX7rmD/nn/+GtzFx7mEP/" +
            "8+9yRM/Tkbrb51s2sfUcHsQYNMgLX832+HYvt6ZkXyn4O9tyl28Z0Q1eewS/bMrwzsWuqBIDgQqiVUBK6buvqXArpJWxSuX4" +
            "WU9/f3+FqabTXvl2d2udj39N4Ni68tv3C6+YcUZX952AoDbkASgHaNwnqOIBXL5WPIZW/Fv7yBziAZYp2seOveJwpQdAvHW5" +
            "rzoIdW3xvcfb7JlY5q5fgZv2TWFXl5kw3qvjU55ew4ug6PoAriOc1X+mO/u3ref973v8S5Hyzn3vpXpuU4oz5OWemhRodhkP" +
            "DiV/MeLfAP6aTUkviRKvnzQ4pPzLibMkUb9E9hpZQsUvknytSV7vVDdovE6yKkyGyudPumBvBg7+YLbfQmMkDCdDSifs6Btn" +
            "+kGfLPgFNILJhnLzAWF5rc3Dj69yaA7+/a8eYP8+o+dWlmS61kS0Q02SUUB/o0v6pyv4y9Cnmuevh3lNIL2Ikn6uvVqaI086" +
            "xbmJgm6R82/xEcLixoyk6NViLvfdu0TVT/a5EkLzEzEMGZL5/2cJL9/4Hic3RU3gZCJ6uzYRvV3YAFMb4QCMW5wy4ijkZgru" +
            "ccZNbRL4FjSGPbNw/R7hxOk1Xnhmnde9rM7/9dcnOTQrnFuOmK41tSWhGAwGi6rFiOaBb/xl1iXc38fjJ2WhfHeDcd2csypX" +
            "U7tE7aEx3bq3MB1aV9S1u7V7frdwHVXoPPIbLa8XdKDyUyqNb1PWJ7Kwow5/ehyWIrihDnef3GTXS4fcv7sZE91RAl7G6xhn" +
            "sRI5ZPKKAzBdi3nkmVVWTnX4yI9M8I9+dhpUubAGjVogsSgtI4iCkYgQi2IJsplfk9D8aROMEgMkXdg/38682VUEVTqGi3PF" +
            "h37rPv1BKq8cST6eVjSU/y1eIV09gFQ0wJXCPi/1bgPrRlVZ6ChfSGQPp9bhxDDrjbYC1myuYhv/e+twgk61Lvh3z8LLdsPS" +
            "aotvP7qGQfmHPz/NJ360zsKaxaowEZIFdoyhY+p0TIAQIRpj0wZvWtz6TS4T9Ax+m0aJmC0ZpvIhz8V8C8sZX7yrp1CQpFeR" +
            "5FGuhaqku/gdqDDQ3N3Nb5RFCrMhfOEUnFiHN+2Gu44PvwV8rMDcjuSjbJ6WLdVNtuMs3192EOYalidfWOPCC21uvjbkH//s" +
            "NK+/xnB20RIGQs0oRiRDLSpCjNAixJi06krWhaaGVynFaYqe/lqSJiAkwS9jZyS96IVc1QHwyt2U+Rcp3gCi4g07dgGk4pNS" +
            "cQNYLX6m7/CgvkGql4DbVvmLU2653XLb4f+xqc9N+OVsWZDrkJCs4vZJFQ0awd5puG43LK20+eaDa9C2/Mr7G/za+5oY4Myi" +
            "pR4KAeqC3CEcguQ9YrAoncTiUMQmByB5ff1bwDO7rSYEZSw/87IUOs38F/tAKBAqeZXtHwS/NijWAOVfRgosjw6xCKOc/X3Z" +
            "c2RhMoTvXRAeX4bX7YCvnwUbj0B9XsysLVsMgUqUn6r73SdrcP1+aASWJ46tsXC8zY3XhfyDD0/xw6+oMb9kUWCi5gxfjUgG" +
            "bQJxAWdS0a4xKEqMEokQihKrJvWudC23ltKEn/ov+BjBPyj7XywolLNAZeAuFT+EUGE45/oA2meRk9Xu9abahf3V2VkkEChA" +
            "+ewJmAyEGLffa0uyv3LxVKGbfGGU5NArHNkN+6bg1IV1Hnpug1og/Nbtk/zSOycIjXBmwTIRuiwd4PW2EiW0SYI/PQAiiWU5" +
            "AbEoBosxmqjakwBN+gJlRaZ4HfthY3/UYL9YN0GhE5zCHqGCCVItvDTZinupOBDDJDotZpRs8J3E62cFvr3gGl+PLsBGNGb2" +
            "1y2OSNnG75EIUwSwkXvcQ9Nw9Rysr3f4xhNrsBRz66vr/PYHJ7npoGF+xdJCadTcDxbk3rV58BcOgrvVs5sgKaisCDZz9XA2" +
            "M1JaXqEjrPrqpQb1oc+4Q/DbUgTnBS4e1pdiQ4LSivZMTSG9lbxatkWXLrPbjP+3bmJvogZfOO26wRMGHlzYRONLtjhAdYTv" +
            "oaP9fCIu8DWGnU24ZjfUbcyjx9dZPtXmyL6A3/6pGd7/uhobbeXMomWilmtcsgAXF/DZYcDP/A4epYckgzAixJ4Syya++XlL" +
            "KG1yJa/7JhRvl0Lh21UEV00xqWi+7CDrAudaChVvwYFUx0K58ZVq/Av/9ujPmoHTLeVLybzvcytJ4yvcIupTLoL4DXrbFmqV" +
            "2jYvcGfqcO0+aISWo+c3OH10g6mG8Jvvn+KTb6sz3RDOLys1ozTqaeBrBnFc8OfMj8EVvw7255k/ENfQlCTTk7zWFkGMFpug" +
            "IrnaZcTgL1ObLwbNOVQfQFQKtwDlHa0qiOTUZ7FQcs/foJ6QanmmXgp1QWRhR0357EnhQhteNgN3n9jica/N1gCydYcqC/zY" +
            "3XrX7Iadk8rJ+Q0ePLoBsfKTb2zyG++uc+2egPkVZWElxfoJpk9Vjn729/C+oRj8+cc0+XzJ2J/c1c0gohnPT0aQ6FDbRnyY" +
            "Uw74Sy34u/sAUlaGpttbcolqgYeXPuLFqsaXFqlPf71RILAWwRdOw1VNOLMOJ1Ovnxf7eZOta4Clz5+NYCKAq3bCniZcWGnx" +
            "jcfWYSXmba+c4Lfe1eQNRwKWN5TTi5Z66PQ24lGamUeVdEMgkykX8sOSOvO7zzM59ZnVgNWmttJDArEdrg8vTh8g/eX9Blhy" +
            "TVLga31dUM7fS4/RyPITmTlIqGddnTS+pgP48jl4bg3euAu+eDIxL5ZNHADdZggkw49QpnjaRg6fXzMHe2dhabXNt59axy7G" +
            "vPr6Gr/+kUneeUOdOFbOLCs1A826ZLPtxpPkmNKftI3gil8pfi6awSNKmT8NVCNlS8HR9WZ+1r8UM34lBMqwWrkW8LJCWgKU" +
            "n4yBswBabnyV/P4192y/6xTsqMF6BE8tbXHja7vqBOlfB/hcfgAcmoMD07DRjvjO82tE5yNetj/kV983w22vqTkJyKoSiNKs" +
            "5erKQEpuhd5hyDK8FLO+eB8zidBRTHFJhZF8NFD6FizDYX0HyeSyCP4iC1TaUqxSnkUrG9v25v61YvmrltwiMuVnInN+Yhke" +
            "XBRumoUHLkAn2oLG1yjBLGPcGNKf1fEz/qHpPPAfOLZO63yHQ7sD/uaHprnjlhpTdeHCmssOTr+TUJu+FSfFzC8Vt0Ih+I27" +
            "xU329XnwSynzyxZxu5dL4GcHQCWfack0H1Jxd0u3/l17mRQhxaF3b2IoG4LBibqsQt0onzuVv7APLQxhdLtV60RGfc17sDmF" +
            "4lZBO06peWAnHJiEVivioRfWWZ/vsG9HwC99YIoPv7bGzklhYQ3mV5V6mMOQcoYv432pyPp+xk+39BpRJBlflOSFNt4LHEiF" +
            "wl6Gx/Mvlohtyw6AkNNgWdEr0pOyS28BrcyO3Toh9XQ+mbtEAovihPo8tQFfOy9cMwXPrjjtTxBeZPgjo3P3ZaiT0pn1UNm3" +
            "G/ZOCe31iIePb7Bxts3uOcOvv2eSj76+zu4pw8Kacn4FaoFSq+VQxJThThK8psD+5DdCEfIkMEQVk/j6iNfhFW9oxXT52urQ" +
            "RW1VU+tyetNcCpEPlzmYIx69qSXD0z6G/ilM0ursn54a68GflPr89AlYjuAVNfjc8b7o6uIGeS9Y1IvODJUDu2HvJKyvRzzy" +
            "bIvW+Tb75gy//u4mH71lgj3ThoV15eyyy/iNWi5HNKVsnxWviXLTlzKYctZPgx91HH9WBHfj/XKWlwHB/2LqdbY88H3CUyBU" +
            "TxTbXetINvarXhFQSX16NidlqxwtNcIyzY/ASkf5winDwSacXne6/y2jPofB8FUc/4B6QLzd0hq7jvVVO2BvU1hZ6/DAsxu0" +
            "L3Q4sCvgkz8+xYdfVWPvjGGxFPhShjklhkeSoPXhT3YATL7l3B0c9ZpbRcgjnm7Hx/vZOR8i+P0i93LJ7uWkXcVshf4UlxRG" +
            "0/xAV08n6nEIqj0Hjuma9JLi2KOF6VD58jnh2LqjPu8+6UEtfZFYnUEPJQnzGUGzDod3wVzNsrAS8e1nWsSLHQ7vC/jk+6a4" +
            "/eY6uyaFxQ3l7IqjNNPAL2T85MG7mJuqwPcORHaAUn4/uSmKWZ+ekIfk1hjFu/9SPwSltRUFWj9zp/DK2lDKm1qS1nc+F5AL" +
            "o6Ts+eZFqm9car1AV1WP85eure93nXSjj6sRPLW8zdSnjFbQljvdVl3gz9bhwG4X+OdWOnzr+Q1YjbhhX41feNsUH7ihzlxD" +
            "XMZftS7wQx+yaHcg+zdAFbuDdBXEKUSS5LXqDv7+eF/GYHcuZeijpT10It2MpL9wHRFC7WJ5NNN7e5dBLpMtTPJrpdQBb8Kr" +
            "KIJT1Eqy4gieXIEHFoVX7YD7L0AUvwjFb58DkcmSE++W3U3h8BRMBMqpxQ2+dXoD1iyvuarGp94zw63X1pisCwsbyrlVpW6U" +
            "hqMZuvC8GL9xpZmkQSjCIrzAN/5NoamSSjBiksDPg1Q8+28ZM/jLRe6ljvulso6TLgMrkTxGw2LbT7vszjPbwhIs8r+urPdQ" +
            "pNIu0+f+J4xy9+lctfjQBfdqbXvwSx9tj3exWXH4PhDYPa0cnBJqGnH8QpuTZ9rQtrz12hqfeO0Eb7+2RmiEpZayvpaI1RLN" +
            "TiHjJ8Gc0ZRZ0EtXPUAv/O9n/DLDU7ItKfP77utGD/xy8F9K7E/hpUz1a+KN6Pq4XPPEXhiIKVg7SOmuSL5IVHOY40/xS9kN" +
            "Wiq9/n03uLqB0y34ynnh8GROfW6Z6nOTygaNIDTKwRlh3zRoJ+a5c23mz26ACj/+sjo//5oJ3nyVM4xdaimqlloAtULgV8AX" +
            "H8sXcHx1MyuHN0kKyb7WYTMp2f+Vcf+ovp1lfC89ktylhPe73EuqMp0K3WuUhDC7IaTM3ov3GCWjahFULeVFAaKa0aB41KdH" +
            "tGLVFb+fPQlLHXj5tGd3oheB+izDHJOuHnKNuUYI+2dhXx3W44iHj6+zcb7DZEP4mZsn+PhrGty8N6ATK8stR5HVgnzyKg1a" +
            "B3XKGb0b6/uZH/IxRv//ZerNRI4sopUdXQoHxoe6wz2x/QZXBr0ZuYiZvmI+SSoOQdb3lm73vFTqH0ZWevCAVevdPTeI5DK2" +
            "1tf7JOONSbfXWjK7w9g6/U+sLvDvPi0cbMKZVqL63M7Ob9Wgub+XwMJ03ckVZmuwstriey+0iRY77Jwy/NwbmnzkxjrX7whY" +
            "i5QTSxZjXBMPhE6sRSwPGFvcKOrj8Syzm9QNT/PsH+f1QvoS+tm7IGLLHs+WsrdmQZnqiYbN+uMK2dbatid8LYvrBj30sO4i" +
            "uXXicDuFpVAcO3gbll0ahlpi7NuiZ/y+pi8DmgR6bPOdUdYqHQuTAXzrglN93rIT7jt18WZt04xvU09NgV0NuGrKwbIziy2e" +
            "eH4DVmOu2xHwUz8yyQdeVmNXM2C1o5xYsYSeaUJkk4JScviUis5SvyQjgo2TSSsPEoEQJLy9zbJ8YlmYHM802E1Vt9ab7iqm" +
            "t7zuC0Ro1jYPeYZ5a0XQsWSFeXlBluooypNus61+MarDONJpbumTspA1I4SqMjL8EG+gpUxB2cxgy1RuTzKi3HvWMJuoPp9I" +
            "VJ/bCSvFH9CPITROmLZnEiSyHF9oceZsC1oxt+yv87EfbvKOq2vMTMByW7mw7gK/ERQdAlNWJijQjVrU5qT5LMHxmT6/oMV3" +
            "BwcPQkm5gBUKDI/0ONzpb9wtcBvM8IyL7zM2y/85PX9QSahxRlRYi+8i2Of30T6GbJV+o94NGeomxH/aYyGyelgrhUg2oT6f" +
            "W4P7F+D6ZOA9irbP7qQAc2Jo1uDALOxpwEY75ulTGyyfa4HAu6+u8zOvnOL1+wOMEZbbML+uhAHUAirwvE9b+kGfBp9HSUoK" +
            "kVAjWkjagZQ0+RVMjZTqhqpZg0LYyWhLqTc7tii+zquMeVRHsk4qq1C6asqqRtcwG6u0eycdKCGb2S2hWnABzlenutkCtflh" +
            "iIGZAP7yrNBJmKCHFrbH6bnA3wNzE3Cg6fD9wlqH7x7dIF7qMFcz/PxNE9z+sgleuTOkY5WVjjvWoYEw3QpUwODecLlonv2M" +
            "F8Bpxk8OQiCkjI2UKcoM4/dYui5SvcYnxbHGyABsLYX+wCBNz+bWGVWkw3EPlMpgg2C2aih+xHOe/3DS3RDz3B78fFUTON9S" +
            "vnzOcHgSXliFxdbWUZ8FmGOdFHlPEw5OuqGH08ttHj/fgrWIq3eEfOT1k9x2pMbBmYD1SJlvWXclmlKASom29ArRbB434+Zz" +
            "GJMK0/xubDmLmwqok4tzhUD6C5mcZkqzZpn0WNki0m/Z3BbLmb0qc2yPsQF4X7dwRiHcyiGHdH2Nlk5onHh9/tVZ4VwbrpmG" +
            "r5zZmoH3AsxRN2d7cBp2TYBay7H5FufOt8Ba3rC7zs+8fpq3HAqZrQkrHWV+3RIkjE6Bi6fI1fvZPp+zVS9Te1y/amKo5h8a" +
            "X4SmXRlfEpeNQNKA1Z6jlWXYI9JH9lQhYd5qIyrpos51aFfJbtGajOQyv9m4HR0CqZQaXFUNibw7lv6SbYV7zrrt7vMtOLri" +
            "irZxn/syzJmtwYEpmKvBSivi4RMtWott6jXDB66u8cHr6ty8M2S6BqsdZX7D8ffV+L4C5mQYXwsBXLgFcmGbFHn8HO4YqYrP" +
            "dEa3G0LkKsxeEKZXu6O8lmq7OXrxLDVHly4I/Z1FdITHHeVAh6rExhBUfU2P7Uml91owwSofijhdcrcIjy8Lr9kBXzubwxSr" +
            "Y8KcZGB+b9NNXE2Icnalw7eOOxpz35Rw200T3Ha4wZEZoRXDStsV46GBepBPRPmCs1ydqZntSFrUigcnRHyYU2wI+SxNelhM" +
            "d2TnKk7pPUjfK/DpsZgmTU+puVUVHN1qTU/JSWegM3jlXvV+VLwOW+iOUMirSzch0AImyz/b4AfoVtlpOg/oH4oE/9971i25" +
            "ixQeX3Ijj8MGf7lpNRG6oN/bgDhOYM58GyLLq/cE3HHzFG89EDI3Iax2lHPrLsiyjO+pKoOCVKFYkBaaVuLTlBCYsgyhuHk8" +
            "rwPKVGWpWKbCdcMrqqVnF7QU1IV1FdIzii4pMVtpvqQ3e1OEV1D9dcO6TCuoGBHUtkJr7VoYmskoyuWjvec/B3hlqHZdt6HA" +
            "SgSPLMK+hpM8r3eGK34zbU4Cc3ZMuMCfrcHSWsSDxzdoLUUEofLeQ3Vuv6bOq3YHBMbh+3Prjs2Z8ILe98qUhMMv43sp4HvN" +
            "Ck6TYHNTpicLf7rxfZrtXfNKu/h9/3kwRvpIADzbyi78TXFW4xKb4NIu4cyw1KV20T662WF8VcSEgC6GxphzgbCnk2l7GOYE" +
            "9cBr3beCAZZi2FCoAUdX8x0bw8CcUNyI4b4m1EU5tdTmsfk2rEUcmDF84KYm77k65OoZIYpd4Ct54Jt8z3fBJ98k3ji5srLE" +
            "5njMSg6BSjqeEr7vZnSkAJ2Kc7lFpaVIv9EF7TGqoAUKuhdgFbl0lJuD+P28dsxp1aIr9ZgETQmqmQCIuBAKnBS4UcRY0GAY" +
            "3lW1d2VVnhu2uJHBmQCOryVGV31mU9KithnCwSnYVYdOFHP8fNvBHGv5oX0BP3XLFG89GBKIgzkXNlxA1EyumYeiQawRCEyR" +
            "wQmMeNm+ODqYZvxsYqsEcagqbFMa05SkCl7B21OGgBaEBFJ5BKQU6v1ghGx7Adw/0fqWI37/SHrAs3z4yu8pVAX/qB5EUpTw" +
            "aBggrTanwxiOug/avqNuWrXL1c8+KfXlCbIkcX2bCeH6afjKWdg5ATvrcKHlJBB4FikAuxsO5kwGsLTe4f4XWnQWOjQa8JPX" +
            "TfDRl9W5eVcACssJmxNIDnOE4gigX4C6G0BL44jeMEpp0qr8OOLh75wRKga4yfj4svNaztenL3QxfPPaoBsiSMlfr7dgLF+2" +
            "qNtnCDxkhVtWoVZn/dx/1Be2SSXgGw36dNGs+VOiJgArPB8C3wc+2Y+Vr1pp1Gt5pah2TdhbhdsOWv7t04bjq/CqnfDYApxv" +
            "uxdsInCrf/Y0wKhycrHFw/Nt2Ig4siPkY29o8v7rahyaMay1YamtGQs0EVLZaDJ0b/fMIJAPY0yuzZEe2N6XGZtSIBaLY63U" +
            "7+TZipJ0WAuhLT1lAVrxompFVkx9WTX/Rhcz+PvEj/SEzMV1vOqZ8lZRoyNJtbVaJ6RJmNaNfieMI77XFqxI72drcGNCu60y" +
            "k5rCiLAcKa+dEz51reUffwseDQzX74AjcWKMFcB6K+bZMy0W59tglFsP1fnoy5q85aDj7pfbyvk1h6XrQU63+ny78Tx6pOSt" +
            "U2E7okZEXFFcFKaJtwxcShJmP7Jz9ab20NvkBXT36mjpK/qVPqIx7buE+uLjnb6+wel4bMk9SsoaHSk+RzrmeqVhJBOCSLsV" +
            "21pY+34YN7k/bttTE3VzqNVWlQocJAVrk6qyrHi+jYE4zl/gUOBcC/67G2F9NeKfPmw5f0YIm1ATZX21A2sRO6eEv37jBB99" +
            "RZ0bdgVE1mX78xtJtg9yuJWp+UoDJFAd+BmuV6vuNjBZ1jcliYKzF9F0HUJXSveL4t743tdEFl/Y8v0pQ72OVfdBBeTp0mrp" +
            "RTkAlbY6Pbh9X5ffe7euFkiZkfYS9DP0VtX6RE06rc7JOVl5MNwpsrDWjr9j4FByUIP+J1wqt0WKOLtz8TKz57hIoLDUhr/3" +
            "5jrvPxzxx4+3+fqFmPXYcnhfyI8fmeK9h2vsmxRW2sqFDeuGFoxrmPlQLJUNpJRkVbbv0t2kC+OSvW/GJPviKrq12VC5FLn7" +
            "AsxJwHwVg9Obnsw3MPfbutTrZa1iQdLgv6To/Z7Bn2CSzEQtBSR+s0uGUrAOs4K3xyGwjQZGO+a7O3bsmE+X5H0B+BA9zuHA" +
            "C1b9zZJu9lclN2aNUzgqwpk1y837Av7XA5OsR0pkJdPhLLaUs2uuGK8F4r2ommS6wrJV0r1YKrmJrC8NKFoLJjApMMak7mld" +
            "+nt/A4pUwx/vcelicXqJeovXu4y1Qlj6sIbyokCf7gnCHtr8bHY8X7vl2967GkE3ze/rEJIKUIxBxPD5TAsU2eCuVkQ7MFKP" +
            "bQ6Duigrfyt81VMgJO5w6rbOJFg68xUSqAfCWhsWrU2UkspKx9GfgTjKNJNXiNfZLLlUpG+BKRacZXOpsulroLYoX5aKjTfl" +
            "bN+Hry8HdzHLawX2Hz7z68DAvzQyf/r7pn0f28tD3xsCygvebiH1OApVLc+i9Pg0Y4JgZTluNZrBZwCMqpq5hjxpla/WQ0nX" +
            "deWrkQqQTirLs0JQaKlpk4jKfDG8oy2TbYQJd18PEphiJON3U9rSoG7xc/I+8BpaQWITGIgQGsfrB8Y7CF4DLPBXCHnTW/jf" +
            "z/teeWc48dWXXpNQWpqBGjCNteng10vQjU2SrT8lLkvzw0GBDerePzxO17pcD2lfNkrt5FSgcaxfP9iUZ1TVmPsSCGwjft8b" +
            "/+hhf65D7YZLM7BqeRA818BkvvUJtWgkCWzj/jjpQRKYRjBB8t4FqgqqRq264AdjNKE4tfAz+IfFeNtT/J/JBb1Ntqh4e7VM" +
            "sT9QrgOkgO51S9aZ6UDIo5dU9q/eAVGKBg+mqfoEZzfFOQ7zZEeo9QODTNTl9xW47z6Mk66L6DnV2ckOj9YCDrYjlNIgkmYW" +
            "iL73T/FAWKuFUxh7YieLuP/vdXw13Tap3VeXeoq9FJCpt7VDrbOiWLdGul0Syny+JuuNlem65PeY5AdQB0IcT5S15Qam3Y0v" +
            "HTDSN8xjBQJzTXNRgn9+zTqTAE+woz0st3XIrZKjZP7BVZDaWq0mUSc6IysrN1533c4FVRUjIqqq4R6RJQv/OjTJqtiqSly6" +
            "m/NFPW5xMinnv8FonGNyTeBHZgviqKfAlFyMPclx7rCmGEGMETHJNLqUM737u5r0lgArWILUJkbyGye1JzE9IY6WmmK65QJ7" +
            "LfR5e23W0UvaltBnXLv7FN40YeWNpkMHv3oDVymTNAwFoKo6OYUQyO8kwR+IJAPaqmoAXVpiV63Jw2HA3nYnh+8+82I1bxLZ" +
            "rqsov5bTa8l6vH3qEWS9asVS5PZTWxXpIQHLOvxqURHWIunS5qCOhBNBvaaUiAhTNRUjOQLtLUQr+vIMM9s6juKyXDHoFhW7" +
            "bl4Ydly0G0CdP5JoQW8vJQH3ZusZHWvAR22tVqMTRfMTtfDGq2aZdy+XWJO8cBYwc3NyHvjfawajZbfzxNlBtEJ/qNrFbRjp" +
            "1spLWmRKfqMYkTx7+5NZ0p19xdPzGM8XJ6kt1GA1QG0gqt4El6TBn485SpHO7Poemg3HFHcGSk9+erzgL94rVcE/NscvW7ti" +
            "eagpLKEr+LUP5zVOg2uc5yK2aHMSI6r/x9Uuxk0S84UBmPTVqK13+G495Ob1NtaJJRUrkg8n+Jbn3qulye5ff0NM+hT4wy+a" +
            "7QkT70rT7lkbVS+L+P2A/NNXO+5vJu2oJLdaUWyWET1MhUVKU/pY+1WZw5YDfVydfT+8Xyx2x21EOTLhYtUA51fTGqBK6DY+" +
            "3teKvXQjHRircXOyZjrt6NFrd4Wvdwb3qCRXlfFeyKQOlJYIv5lYxyXVJmiCaUR7K/TEy+zpILm//sf/vCCzC8+pRvyvBcQY" +
            "VwsYyejRwkpQUqyfrgBFMgo1d10QkxC4psLL0p8FpjRNlP1cFVCnlxR3UEbTAZqelETo2tU7hkPGi1Mx6ICVubplmp5hriUx" +
            "oiZAjPLfi0g7ifHsoUzpxYtVNWjW5IvrHfs7zTqBVeLCEmXpuSQpu8elJ0tMl6GqUSUkJhDN/qQUpClbj3icZHrAFJyOD5VE" +
            "m++3EiRbJO3Zihe0PFp29JC+t4D/Qo4jz80DX3oGSHmkdItEmRf9GPgHXDdxnY37O1lLPDcXhusr9nev2VP7fFL4xn17Mh4U" +
            "mljv8LVGjdctr8exMSZQv3WhSlzlC6S+8k+6rnJLj2tP0y3yPRYcZJ3mPHitwmon0fYk/8+UCtjyDTVZNwXN/ahak828mNvV" +
            "2a2SHaBuCcfOiwiBOraHenVciDgG5MnLXo2nZ2pBaz26/5pd4VuAtg99Km8ADwohIuuR5ec2OnaxFgZGrVp/52O6UrV85xYg" +
            "Repnb/yhcS3spi0UbWnTK214pVr9rDHmiujC0IsRSbU9xrcsKakti11bqrvY2xT8mskDpJrWUx2LDVFfGuJt6xTPomRbPfl1" +
            "+5SloxpgqUeLqlVbqwdBHNkLBvsLIrLhx3bfA5CyQqoazDbk0fW2+XhosEEAcVLhSgpQhXzlo6+eHMLDxefsR3N3zhdQ+Noe" +
            "KsYPCzu1ZDPChPGCtAx3BlGcOoZFTFosiO/FqTre6R4qKxebnQOkByM17lQHKtl6fp3mjSkbhEZrIZHttD9+ZPfEQwn0sVVf" +
            "b/pw2rGqhrun5S/aMb/UqBsTGFRVrCaOVoVcXBBiaOU8bDkQxTOILSyPqBgKKTM2aRNNK4Ki/DhbQvFtIdzpdjzY/E4sHU6b" +
            "sMngLw8r9h9a6UUi9HVwHvFZEa8Fp6o2MMZOTgbB2mr0q0d2N++6914Ny7ifYZ3hRCS6VzWcFvn9pXWdmGyY391oWY0tViQx" +
            "4xZxLTHtlXGGk+rKMJBEnM1iesCsH+jSJ59vQrYwbqOml+J/01jfo4ek4gnT7k214wV6j9NV6c6gefYuD+f0JQV0/ALel8oY" +
            "QVCNjRFpTgbh/FLn128+MPFv7r1Xw1tvlajf4wyskG4Vie69V8PZpvzfG23+ej00cb1mTKwai29ML1Lk74TqWduBkKT/mykd" +
            "FhmU6rPgl4uS+SlAHqnEzJtKzFK6bb2/b6447zHYrkMcdvG1CSM8fzLeYS3lELHWxmEtDBoNE6+sxH/z5v313xkm+BklKlQ1" +
            "FJFofkV/olnnDyZq7Fhcs5FI6Rbxd6t6EgrtY9/RlQl0SEwOrLV1gNd93lSTZGOKDGCAxhnCGJblGfUAaKWcbLCKUpOu965J" +
            "swk1p78n2R9U1y45/LmVmMiOOKo4Zp2ebCJNx2s6k9O1WtyJLwTozx+aq302jdVhE+qwC9Sie1XDXdPy6bUWb2tFfG/HpAmT" +
            "7Ue2O+MqPtHeb4y7fCukrNEwt4SUmlPpsu8MJkj3IrntoThlQPAP1qv3k5cXi1AtvN+qzeqVz3mV+XHhKMpI+hzdRKMuURQk" +
            "gmq1VtXO7azVbGS/o1H8jkNztc/eO0LwMw4uSJsJJ1Wn5tr8ExPy68bA6rqNUOc0WNwXkGcN9RwtdcyM5MuCFWezmEk0+vka" +
            "JV84WZfKG2AzG1KUQfurxhCz9VwXpP0g+lg3gBZXjPV5gbS0cjGXq59fiejEg5OWjpn5s6dRVRXs1GQttAq2Y/9VuHHqf7jq" +
            "qqvWqhpdW34AUvVoSistrOhttdD+k8kJ87q1GNotG6E2yMcqc58Xf+rTjvytk2V+JV38Wme0rF4+AJuZp9WeOpfyzz2+hWD3" +
            "5hQdzlezxwEoOiB78yoDit/KT0gWqAtwfjXuewB0bLjjfbmqrdXCcGpaaG/Y+23H/o+Hd9c+W47JUWvKMeowsaoqqhrsmJa7" +
            "vnnSvGU95rfFcmLHpAknJkJR1VghLsoEJWMRZJO0n/ShOkfL4DpyJ5iSHXiv/zuOoE39kVLNp+e6J/KHCx7t6c2ZzlwPQx9p" +
            "1bs+TtQ9CuwR36zDeLFCHNZCs2NnLRSxp9Y34r/bPn/0LYd31z6rqkEy1GU3SyWPSxNm187JZd03U4t/WUQ+1aybIxZYXbfE" +
            "1sYJtDDuZuieDtLh9R35ELYmEGgEteFkXfqQlKMsgxhinayOSXNWUbdDrh0qW2/tmjLIgAVzMsBFgR6LEPH0/lU3gI7o758O" +
            "rqSxLyYIpqYCjIFWyx5D9fdqQfB7B2fkDMAfqwY/PSLk2fID4OmHTHoQLqjuMBvxh0Mjn+hE+o65ySBUYCOGVsu63UVuEi1p" +
            "GgsDAHz2zKbmuXg1gK1wU+4VfP4B2GqmZ0tkzKS2IUWp+dABVDjcwu4pb2Rfi70s8XqYOeNT4T9UkiKXh9nLNYBPBPaa2NI8" +
            "U6RTsYKIqddC6g3nBbW6FLdE5Cu1uv6HThT82TU75EKadAFbJW14UQ6AfxDuu4/A518XN/SG0PDjkdr3YHkDyOFmw2WOGOhE" +
            "EMfpPLH23YepSFcRvNZRSp4pfeu3qbrkDZsRHBqqDGmla2Sx2zJshOX1+e/qFeZdlGfFz6x9ltXtTm6AlA6Wypup16NK4feR" +
            "Cn1++pjn0htgCD8pp/kyBEYIa471UwsbGxZRPRbU5Luq5gsT8Ln9O+QpH21sVeBvywEoHAQI3gmx/8MuLOjOThC9rjERvjqO" +
            "7GuAl1vlEMJuI0xZq82+HqX+3Gl6A0Rdi9K7PJQ18TcyAo3aeL941ehixomPsK92YOe18rG0RydAKmz4cgc9I90jkdKnyPZv" +
            "imyrenn7Y6F61mwHwoU1m4xEFulTqeqQq22JkUVV5i3muBH7bGDC71vLg4d38qCIy/QAf//vq3nnOzHvfGcxlrbq7f8HHXQC" +
            "sl/brrwAAAAASUVORK5CYII="

    }
}