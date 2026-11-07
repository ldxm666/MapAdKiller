package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
* 百度地图**原生启动图标**（{@code com.baidu.BaiduMap} 22.1.0 的自适应图标：
* {@code mipmap/ic_launcher_background} + {@code ic_launcher_foreground} 两层合成，192x192 PNG）。
*
* <h3>为什么内嵌</h3>
* 装了 App 由 {@code MainActivity.appIcon()} 走 loadIcon 取真图标；**未安装**时设备上没有任何图标可取。
* 内嵌一份原图，「未安装」那行也能显示百度自己的图标，而不是品牌色占位。
*/
class BaiduIconAsset private constructor() {

    companion object {
        @Volatile
        private var cached: Bitmap? = null

        @Volatile
        private var failed = false

        @JvmStatic
        fun bitmap(): Bitmap? {
            if (cached != null || failed) return cached
            synchronized(BaiduIconAsset::class.java) {
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
        private const val B64 = "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAAm40lEQVR42u2deZxlZ1nnv+97lnvrVlVX9ZLuTro7G9mA7iQkKogwIiQERcU4wkBEQRYV0RkY" +
            "ZkTjMqwzjCuoMzqOKIv6QTAIKoozCCqORhZJJ+lOQhIgnXR636qqq+rec877zB/vOXXPPXXuVlvfW/U+n8/9dFfVuee9573P731/z/oqERGcONmgot0UOHEA" +
            "cOLEAcCJEwcAJ04cAJw4cQBw4sQBwIkTBwAnThwAnDhxAHDixAHAiRMHACdOHACcOHEAcOLEAcCJEwcAJ04cAJw4cQBw4sQBwIkTBwAnThwAnDhxAHDixAHA" +
            "iRMHACdOHACcOHEAcOLEAcCJEwcAJ04cAJw4cQBw4sQBwIkTBwAnThwAnDhxAHDixAHAiRMHACdOHACcOHEAcOLEAcCJEwcAJ04cAJw4cQBw4sQBwIkTBwAn" +
            "ThwAnDjpJr6bgjUQEftK/y8iKKVAqba/E0Bptz6ttiiR7JtxsioKD7BURRYBYwAFOgcYJw4AAynGlCv8mbPIyZPIY4eQQ4eQM2egXkfOz4IYqI6gaiOoTeOw" +
            "Zw/68stRF10E27b2dn8njgJdcMUXwNMLK7e57wBy990kX/kKcuBB9JkzBAuruEpJTstaZH8nQixCMjqG97TrUNfvRT3rWegbb4BKZWE8EUF5npt7twNcYKpj" +
            "DKSKKIceh898hsan/w/egw/haw0CiRgiEUTrUtUvA5SvFEFmE4gQ7dmDf+sL4LZbUddd53YEB4ABWPVTxZNHHyX+o49gPvVXVOp1EGGuMK2qz2mWPN9XihEB" +
            "tKKhFN6tt6Be8TL0M55h/54kCyB04gCwdqv+1BTJ7/4e5iMfJYhjosQQa6u4KludV2pYrRERPBEqnkdsDOo7X4T+yZ9A7dndNLzdbuAAsBarfvLXn4b3/Q+8" +
            "o0doJAmJ1iDS90rfNxByO8OI1kTj4+jXvxbvh1/pdgMHgFWUVLFkbo7kl38N/66PIyLMpearugDTaJQiQAi0h3nuc1C/+HPWe5QDqhMHgBVb+c0TT5D89M8S" +
            "HHyAObH++ZWmOkvaEZRiBEVy8U74r+/Eu+kZbidwAFipld+ApzEPPIh505vxjx63q/4FVvwyG2FEIK7VUO95F97zvt2BwAFgZWhPcs9+zE+9Cf/cFHMIekCn" +
            "TJTCF9C+h3r3O9Hf9SJHhxwAlqhMSYLyPOSRR4lf+2N4Z89QXwMjd9lsTVsQKK3Rv/levOc+x4HAAaBfLRJQICdOEr/6NQSHn2RuCJQ/T4d8QNVqqN/9bbx9" +
            "ex0IHAD6T2SLfvQNBF/6slX+AeP8vdChqlJEuy4h/OM/hIlNC0E1J7h6gI4A0Jr4t//X0Cp/FnmeB8Inj9B4x7uaO5sTB4Bu7k65Zz/yu++nXkxrHjYQGMM8" +
            "QvjZz2E++Rc2WW8IwewAsFYrP0AUEf33X8JHSIaI97fHtM0ujX79N+D0aUuBHAgcAEoNX61J7vozwgceYl4G193Z1xcsQkOEyrmzRP/zdxayS504ABR4v4LZ" +
            "WRq///vEIitrLGq9+LWWVAhoiGA+8Unk0CE7vtsFHABa3Z6K5M//kpHjJ4lW2vA9PwvT0zA9Y1+zs2vqjVEiJEAlTog/9IfOE+TcoMUNQFBxzPztLyV84gkb" +
            "8FopAIjg/8irYM8eiGMIfHj4UeIPfshWeK3R9ItS+EphaiOEf3YXaucOO/YGB4MriTQGpTXy5X8lePxxGqtg+Orv/W701Vc3h7xnP/ze+6FaXTMAKBFiYGRu" +
            "nuSzn8W74xWIMRu+rNJRoFT/kk/9NZ5apc4LMzM2r2h+3v47M3NBVl6Vjpn89afBiGu7suEBIGJ949PTxP/0/zDGdK/XXaoR7HnN1wVSPBGhbgzcfxD5+tec" +
            "S3SjA0DSL18efpTgxCnqSg1l1LcfGmSAUAT5yn5nAW90AGQkxHzxi3hpSeMyOcbi1d7zutOdsves8o6SfOELLRTQGcEbEgEpJ77vPjxZJsUBiCJoNCBJFnYX" +
            "RKz3p9NnOH8eiaKF3kBKaxgba/+e6WkkSZq38H0YHe0NwCntMQ89BPV60xO1Qb1B/kZPeiOKkMNH7VLYb5Q0M5pnZ61xu2M7+oorYHISNVJdWGHV1q3lmZhK" +
            "Qb2O/pZvRl28M41GK+T8LOaf7y7n50qhX/B820Uuu/7EScyXvgy+3/3zixAB8uQx5NwUavtFDgAbWs5NYY4cJkprfPtSfhGYmUHffBPeHa9Af9u3orZv775T" +
            "5H6W2Vm8174G7wXf0dTRUyepv/DFdoXOqFlmsGqN//N3oi+7NEdnvoR5+R0wPt4VAJk7NKzPI8eOWQA4CrRBdwClMCdPEszOEQuoXgmxUnbFNwb/zrfi/8ir" +
            "W3m7Ma2px55uv8KmFIgksRQqCODsuS6gPZdenwbW+nWrao0HmMefgH17HQA2dPbn+fOWeSvVWwAs5zoMfvO9eLe8wN4rx8mtkaoW2Rpdjdqs4VY3Izi7xsgS" +
            "jWABNDIz0zoXDgAbzwBmbq4/V5hSMDeH/863W+WP46anZ1gCS1b/kfPncd2hN7jI7Cw6d1BF15V3ehr97c/Fv+PldtX3/WYhzWOHSD7+Z5gDDyDzcwu7RfD2" +
            "X0RfddXg1OXmwO8AsMFFBUF6HEuP1Mf38V/32kWGafI3/5fozp9HTpxABWHzfiI2E3SQqEb22cPQAWCj5wBRqSBIbwGheh31lCvRN9/cVH7Pwxw8SPSW/wxx" +
            "jNq2rdUeGOSGtdl5A7hI8IZFgKpWMb0GuxoN9L69UAmt8qdUIv7Ah2yef61mPTnGtL4GMQQuDgAbGwAZD56YsPUwvdCTJEHt2EFLp+j5eeTgA1CtdI74DiAC" +
            "9OTEhm+V4gCwaRPxaA2veChFuz2juGpm6Q/DpETGYEQgA7PbATawETyxCb1zJ4HWXZVYaY2cOtWkRCIwOoraudOu/kPgBhWl8IB6EMCO7Q4AG3oHSAyEIfry" +
            "S7vnAYmA7yMPP7LIA+Td/hKbtpDFApTqK6uiBGnpoQOqNW11hZ47VBp92WXodjlKDgAbxQy2Cq+vv763plmVCubAQeTxx1vygbzvewn6jpcjx45bIGSnRi4l" +
            "q1QEtWM76tJL4ezZdIwVTgHXCn3dNRAENqvUAWCDb4PPuJHEmIWSwbYSBHD6NPFH71q0Y4T/7d0Eb/sFe16XHzR7jEp3F6vkjWcRqFYJ3vE21L69qUtVWu+3" +
            "XNAL6Jue0VImiYsDbEAWlHJ2dd21NC7ZReXIk9S1bl8VZgyMjpL80R/jveR70Fdf1TyEQin8H30d/g/9IOYbj0GjvmA566uvanucqUJBtqPkdgG99+mEH/8Y" +
            "8tWHmy7XlHrpK69Ir1X9839RNCoB4TOf6VY+VxCT8viREYLnfhv6Y3/akx3AzAzRW36ayof/ACYmmvlAIjAygn7qdZ09T/n7hSHmc38Hb3xDa5TWGFS1irp+" +
            "X3dPVh/8JwTq1++DPbtdy3RHgXITccvziXtpiWIM1GrIgYM0XvN65BuPWVBkK3SSWEBEUfOVpk4vCo4ZA7URzD37bTDN8yxdyiLJWcpz/l6d7tdL7E8pe+C2" +
            "EweAFspx800kV15JtZfWhcbA+Bhm/73U73gl8R98EJmaaqYp+761F7JXlrLs+/bfWq2504hAbZT4V36N+IMftlZqdr3n2Xz//L263a/Dc/oK6rVR9C23uLMC" +
            "HAUqtEb0fYI7/h28+z22Uxw9nB82OgpnzxG//V0kH/5D9LOeidr7dNT27ah8jW6lYlfuOLaepP33WuXN9yVFE7/jXZhP/w36lhegrnoKKgy7U7IwRO65p3m/" +
            "Dot/oDTRi29Dbdvq6I9rjVhSHDM3x/ztL8U/epSo15MgM9///Lx9KV1inKrW9guetziinNUXZ9Vh/Shn2f2Kxq9SSBASfvxPUHv2uFPl3Q5QEhSr1Qh/7PXo" +
            "d7yLqGdenfL+MOyj1aEsPq0lc3GOjvZfnN/FPSpAqBTRS7/fxhfSo1+duB1gsRIlCY1XvYbwwEFm18EZAUYpQqUwWzYT3vVRZHIyDYY5ADgjuMylGAQEd76V" +
            "BiltGHJDUWuFr8B7y5thyxan/A4AXTxCxqD27kX9+OupKD3UnhLRmiqK+gtvxfvuFzvD11GgPo9JfcNPEtz9BWbFDB0VMkoxohTxpZcSfPgDyKZxm/bgXJ8O" +
            "AD2fFnnmLNEP/pA9KBvQxgyN8gdao0ZG8D7wftS11yBJsuHPAnAUqB8qlCSozZP4v/Qe4koFXyylGIZ8f19rAhHUf/l51LXX2Gdxyu8A0Jekjar03qfjvfud" +
            "6LSQZJCNYkmzO0Mg/qk34r3otmaynhMHgKXuBPrWF8DP/CdCpayLfkBBoHyPqlJEL3sp/ute45TfAWBldgJJEvxXvJz49a+lqjRKq8H0+AhEt91GcOdbXaTX" +
            "AWBl6wYkSfDf+Abil/0AVdRA2QOiNSNKEX3rMwne9bZmdqjz+DgArFSQTGkNicG/82eIXnQbI0oPBAiMUoygiK6/nuBXftlmizrld27QVU2aM4b4TW/B//w/" +
            "MrfSh2r3qfw1pWlceSXB+38HtWWLC3Y5AKxBjEApZG6O6A0/RXjP/gsSKLPKr4h3Xoz3gd9LT5hxyu8o0BoV0KhajeB9v0Z8zVXU1jhnyChFBYgnN6N/69ed" +
            "8jsAXKBA2eQE+r2/SrRzB1UUZg1AIFrjK4WMjKDe96v2BPo4ccrvAMDaB8oSg961C++3foNkYhMhqxstzrq6aaVQ73k33o03pGcUOF+/A8AFAYHNHtVXX4X6" +
            "5fdAGK5qtFihCBWoO9+K9x3Pa3ajcOIAcCHpkMQx3rOeifq5nyFErUrWpWhNVSviV/0w3ste2jydxokDABc8BcGHJMG7/ftovPIOqitsFGepzY1nPxv/zf/B" +
            "GbzODcpgl1T+8KsJDz5oYwTLbWWoNQFgJicJ7vqo6+jgdoABLqlUCoIA/+1vI67VVqSkUgEe4L/tF6zyJ87j4wAw4K0W9TVXw0/+BOEylV+UoqoU8b/9fvR3" +
            "PK95jrATR4EGmg0ZgwIar/4Rwv33M9drj6ES6iM7thN87E9gfKy5yzhxO8BgbwS2WVbwsz9L5Pt2kvtUXCWCD6g3vwkmNjWb5jpxABgWKqSeeh285HupKIVZ" +
            "AvWZv/FGvBe90FEfB4Dh9QwFP/Y6GrVRm77Q4wqulCJSivDfv9HuJo6lOgAMa9IcO3agf+B2QqV6OulIlLIFN89+Fvrmm1xZ42pv1sYYWb8LsKCU6ngMkFmD" +
            "1GmOHKVx+w+g5+ZI6HImsVb4KNT//m30t9y8qj5/ATRex/nL5nC9ij81NbXulN4uwBrf96lUKnglK+hMAmdiaJhV3mCNQW2+mMlbb2PLX/45s6mHqN3qP4Jm" +
            "Zt+VnL9hFFN/AKU9SFavj0Soa4zqbVTUWLG/BMYYoiii0WhgUi/WegODvx68oNkzKKXwg4AwCPB9H12yck7HVvHnzNo4VpSxq+j093wfo3/xSXSq6G13AQX1" +
            "27+dWCeQCIvN50Kr9UU/tz8Zvuy98+Yc82aaih5jTG+lojYtnMvqeR6e5xGGIUmS0Gg0iON4XYHBXw8Ux/d9giAgDMNSpRdgOoEzEcwl9vvVa1A2qwDjeSgR" +
            "tt20F7X36VTuP8Bcvrwyt/qHwPymCUaf/73UOQtaUKhFqqtye4jkDhLOq/miE+4X3qUW7VICzJsp6maaih5nVG+lqjblzBmN1pogCFp2hSRJhp4i+cO62nue" +
            "RxCGhEHQQnHyCiDAVLriz5slHay4PBMA8BTsCgwjgUf8wlvhwMGFQFnR8+MpRfK85xBu3sNkXOMsT6ZnGauFfzM1L9sDpPAbofWcbemwF2i7JDBvppg3U1TU" +
            "GGPethQIzTtoralUKlQqFbsrRBFRSpGGEQz+sPH6bKX3fb90slWqeNOxXfHnUzt0rf0oCeAr2F3BenUA7988h/r7fhM/SUgKNEhEMID//OcjIoyoSZT2OJM8" +
            "nj6Xzh3una3mUljdBVnYMVqvUbl9Q+XgsPgsG89GseU8p+MZAjXKmN5GVU8sutrzPEY8j2qlQhzHRFFEFEVDRZH8YeD1QRAsvHQHj0gkcC5uGrcq9fOKQLxW" +
            "brXsRBYNuypQ1SBilb1x+RU0nvpUxu+/nzmaB2g36c8mKjffjFYKtGJETaKVx6n4G5j0CZpqKwWKUzwOW5XQoHJPUKff1c00dTNFoGuM6q2M6Ek8gkW7V/b9" +
            "GGOI44RGo04cxy3fowNAH7ze833CdFK9HvzgdWOVvyEwqmFMX9gendtCCFWzX6ckCcdjj/AZNzN+4CCIaVE1T2vq19/A+eoYFy24PoWKGmerfwXnzSmUaES1" +
            "rvkWR9L8WUmrrSC07BWowrovLWfI29aPGZDFHnkGChFDw5zHkFDTm/GptAl/aMJQE4YBSZJYeyGKMANqL/gDx+tTpff7rHaqaNgeDt4Kk33dJ2PFbALqpm9G" +
            "PvzBxblDQHTTN3EugqoI42Hz3RU1RsUbY/jKpq0XqVqtLtCjOI5J0nOQBwEM/oXn9YogCDvyenpw9A2q4oONOZyOFErD/DXXMFetEszNEqd2gIhgRJh/2l4E" +
            "OBYpqgEEqvMMyAA8W69XZgubiFgwxDFx1CCLw14oMPiDzOvzHp1hdbTFAkfqTS+NmdxMtOcyao98lUQs7QgQ5sY30bjscgRoiOJwHS6rUnByLlURLyQZbP2k" +
            "SinC0C54xlQXKFKS2gtrDQT/Qvjre+X12VTMGjgdNQ3bQUr6jAXGPUu/yj7b4br1RPnpUayiNfVrr4NHvkqidZr2rDh/8S4a45vAWPN1OoYTDXvfom/fkHAm" +
            "PkRDZtH4A7Y/CqEeY1RvJVS1tkDI7IUWl2qjQRRFa0qR/LVwXWaI9zyv40MVv+jzCRxrWOWPZMCUP13dJ3zr6lQlz3EisnEIT2U5RwoxwsyLX8KWv/gEo0ph" +
            "RMDzmXnRdyFaoxKD8axX/ngDRjwLsPzdNR6jehtnG/fQkHm08lc7q6nPbQ88FTDu72Czt5tQjXYEwoJLdWSEarVKHMdrFnVWZ86ckVVJSfB9wjAkCIKuH76o" +
            "+NMJHK3D2RgSsQo0aMofCUwGcM1Ia3Ate5bzCXxtbnHASYxBac3o3/0tF/3xh9Dnpjj7PS/h9A++qiUqnL0vUHBVrWgPkLp953kyuo95M4OHXwiPXdj5EQxG" +
            "EgsEbzuT/i4qarwrEGjJJTQtYFgNl+qKAGDBdZmLzuoeMhiLij8Vw9GGVXyTU3wZQOXfGsBVI+WR5Vjgq7PWNatLPr8SWThkQ6RVHYpRWpMC7cqR4nzZn2Lq" +
            "HG7sZ97MDBwdsmkchkQSPOUz7l3EpL+7Jc2iPHljsWQu1YwirZS9sGQASJpJ5qXR2X5cl8VHPpdTfBlQxc/Tnm0hPGVkce5N9v+vz8HJyEaCpY3RqoxB0iIZ" +
            "ZQwml7ZdvF8isKcKO8J2IGhwpHEfs8lZPBUMzE7QCgTBkFj65m1li3/pkoAALOwKKxF17gsAxZSEIAjxfa/nwYuPeCa2VGcqtn8bVMXPr/zbU+Vv92wnIguA" +
            "ovKvhD/luhqMeeUjJxJxOLqXuQEFQSs5StBoat5WNnu7qenNSwLCgks1ioiiGJH+wdAVAEvl9e0UX7BG7bGGVXyVJqipAfb1Zyv/jhCu6KD8cwYOnre0pdSj" +
            "pVopz6IbtEnPVmlu0YiGp41acJWCgIgnG/czm5weYBC07ggKRU1vYYu/h5resiQgZPbCUrJU2wKghdf34bpsp/gGOJUq/vk0McdTgx/kypT/4krTL1/2nAIc" +
            "nIGppKmgkiP3IpCYZkqCLGR42jdrrVApABQ2BUHlJlEDkYHtFWt7LFaPtIiFhCcb93HenCJtrDLgs5sHwmYm/EsY1xflUvakJGWvO0XK2wuddoUWAORTEnw/" +
            "IAz7T0koKn4iOcU3oGQ4FD+v/LsqloO3y7XPeP8TdeutkcLfkwRiBDGKxAhxmqRnCsavh2047SkLCE+ndQsFA/uqGuwM24NAMByNDjKVHBsCELQCAWBET7DZ" +
            "382YvqglC7ZfIIhI1yxVdfr0aclOQgxygaqlGBVFxT8RWV/2bNJMSR6mlIZEYHfV+vk7Pe/JCB483wR2flcwxq7cjUSIEohSDiQoTGKDG0rZG3lK4WsIPAi0" +
            "IgC0ZyliMSq+b8zaA+2JgnAkOsi55AiePbmAYUkgyTJfq3qczd5uxr3tqDShfSlAsN40IUoN5xaX6vT0tGQGrV5itUj+S4jFRjCPNSwn1kOo+OQ8L7sqnVf+" +
            "uoGvTNvnLtoxxkAiQj2B+RjqsZAAsUlBILm2olrhKQuiiqeo+hB64HuqpXVH5hUa9+H6sab9VP69CEfjBzgXH0lTmGWoMqmEBIOhqseZ9HaxyduZK+KXJSeD" +
            "5F2qy2qNmP8IDWNXwmON1P+tyn3gwyAGuLQKF4ftpzl7rntn4FSj6fXJjF2F5fyJWMWfT7JdQDBi+X8eANYmUvgKKqFixINAg6dVS6whsxEiY3ena2rdVEE4" +
            "Fj/E2fjwkNChorlsUz8EQ0WNMeFfzCbv4lxNgiwrK2pJAMgPWTdwPLKrft2k/HVIFT8LPF0xUp6DU8b7H51r5f35XSCjQI1EiA00jGBsrbu9RlkgpCn++MpS" +
            "nkArfC2EnsbT5Z8hs0/2jpXFBxZ/2hPRI5xOHhtCEDShkAEhUDUmvUuY8C7GU+GygNAXAPJDzBvL70+mSWp6yBU/W/mvqPam/Gdi+PJUrrNYex5CLJAYIREL" +
            "sEQEjc0DymytLACoNfjapk770rQPFup+pbnLkBrT3zIBNd1dBU7ED3MqPjRkdKgMCAaRhECPMOHZHSFQ1SXZCF0BUJzUOWNpzqlU8Yd9xc8XlF85AtuC7srf" +
            "MPCFKZulqntoTpL5/kUsyERkIcU589s0FVpZh4Eqj6EU8RYLbAng5k3NkvlO3+Sp+GucjL8+tDtBfgYEgyEhUBUmvJ1MeLsXgNBrvlFbABSVYDZpKn4s62PF" +
            "zwroFTa6uyXovIpmf7tn2qY5V3Th+YWFssKy7yA7RCbvYFuwA3Tu0pL7CK32Ql4iY12jvdgDoDiVfIOT0aNp7tBwS7YjGBJ8VWGTZzNQAzXSExAWAUBKqpmy" +
            "lOREBjtdoW/lT5XxmppNa+5F+R+bh/tm0mCXtNKTtsayLFbefJCs7P2dViXVpgvFN413pm/5m5yJH+d4/HDH1ohDCwRCNvnbmewhFXsBAO1Sks8McGbmcld+" +
            "DVzdh/JPxfBPZ5c2B6s5d5lrtOLBt03alImeQJA8zvHGV9N6AtYNEGyaRYyHz5i3Pc1ALU/FVkZEyjIzz61Dxc8rv6fg2lq3YFLOmyPwj2ftvLRLdJNl1c/2" +
            "/34pSda7OIRnTvTiE7FXnE0Ocyx6KN0J1s83nQeCxmfM28akt5sRPdHaLy/bAc7mFF/WoeLnV8pQW9oz2qPyK6y//5E5qKhiF7bVKTYvM3h76QDaENg3ane2" +
            "XkFwLjnCsejBNO1gfX3reSAoPMa8i9js72ZEWSCoUw2R49H6Vvyi8l9bg1ofyn+4Dnefa63KklX0SLUDRq9jCvDcSVu00ysIps0xjjYeWGKiwfD4+ywQNGPe" +
            "Nia8S1B3n7VbwHpV/LzyVz278o/o3pV/JoHPnk5THXIT1Mkr06EpQtejhhUgRUO3kEKtOhgrCtsFb9yD529pBuk6f0yr9jPmBEcaB9Lr9TrWhiYQtFa59N11" +
            "+rix2OLy63pU/rxCfnnKuoBJ7QCT2hCSsw2Kr8zdKdm10voqu94Urm+5JjdeNqa0GS9JF7Ozsc1R6j3hQBjTF3FJuBeldM5BvN4kLeoisABYr4qfV/4xH64b" +
            "bfrtVY8R7wOz8GTd0iaTD2jRqnRSVFpagZKIfbWApwiSslfJGJK7p1mILLfeOzZ2UXtk1totve3s9qpRvY1Lgr0oPGTdgiBnBH/h3Po8gW1B+T24ZjTXp7NH" +
            "5T9ah8+daT1ETalyZlP09Xfk8W2CZS33LokRlN2nF3qlgFu2dA/yFWdg3pzjcONektR4XK/LpF7Pyj+ervz9Kn/dwN1TzdLGIt2RAu2ghJ6U0Z6W63Pp0BTv" +
            "XdhViuOV7hIlL8RGif/lnJ2PfjhyVU+wK7whLa1M1u1OoNer8k/61tuT2Tf9fH13T1kOrZXl1IbFtCbj23l6k7D4+vzvyu5V/HvZNS1j9jmep+BYBF+e7sfJ" +
            "YV2HVb2JXeEN+KqybkGg15vyRwKbA+vt6aczQ8Z2HzwPX5u1u0bSSfGLxnDhXqZYHNPlld9JTMm9szHLxsu/pzhejPUEHTxf3qirm2FcVePsTkFgJF53IPDX" +
            "08NEYrM5n1JrJuqpno8LtandX5yyq2ZS0r1B9RCkWklXcq/jdXNkZAeF3H3Ozs8mv9e5sU8TqlF2hzdwuHEfDTOLp/yB7TixIXeAjPZsD2wEVC8hDSES+PwZ" +
            "G0ld8LIU3Y+UuySTolszu7aNG1PK7pt7bz/jJfkx27lN07mYNfD5s/Y9/frNMxCEukbC+tkJ9HpR/h2hXflZQhGMwhqKxxq2ftlIqxGadHiZDspsij8XFDcp" +
            "MXxNlzFNGwAlhfGKoIzFbvePz8O/Tve7U9mrAzXC7vBGqnoMQ7QuQOCvB+XfWYHLqyzxpHTrL79vxvL+WHorROlWPyE9XF/WBnE57+/2GU1advmvUzZt+rJq" +
            "P4WEGQiq7A5v5InGfubN9AC2Z98gO0C+YdVSlV9hvT2fP9vs0yMsdm2WNcEqvij5udv1rPD7u70nf90/nLFpHkvZCTxCdoc3MOpNDv1OMJSBsIWGVVXYU1n6" +
            "uSUi8MkTtqFVRbV6UbqurGpxIKybIUqxY1wfmaBlwbJex1uoMMvtenUDV9bgu7Yt5bSZrAtdzJON+5gxp/EJhtIw1sN67NCeVPmXMuWZMnxhCr4xb92EScHw" +
            "LDVAWcyzF15t3lPk6Z2M625j5u2GpMP1xfGSwt9ibHrHw7OWDvXvuVLpQR0+l4T7GPO2kgzpTjB0AEgELhtp37CqV97/2Dz8y1Rz5e/kj+/2oo1PvlfFpgMY" +
            "ehmzOEa38bJYRqjhn8/ZfKdlgSDYx5i3fShBMFQUyKTKvzNcuvJnKc4fOQYzbaq71BJy/tUy6wT6fb9agboEnZ1048PLd9pkwaU2xBSEI9EBpoemH+mQ7QAG" +
            "y1mXqvx5+dvTtsjfU4Xoao5eJCXuy07++4TO/v4yX7/pNmbZfZYwXmn6dkqFPGXdv5893SwXXQp0FYpLgr1MeBcP1U7gD0vPnqd06dnT6+r/pSl44LytC4i7" +
            "Wb3SpmKrzEfaT7i25P2qmAnazefZadtq89kWMlULSXgVDfdOW6fCDWPNhgFLkR3BU1HK42z8xFDsBP6gF6+D7Ye/ZQWU/0gd/v5Mmucjhfvl/f/SvkCm2zWl" +
            "vERK9FgKn096v0/XMaXN4SRtPr+I7UP6udO2qH77MnZZBezwr0WhOB09jqcGGwT+oPfsuaoGm/3l056GwKdOWc4bqNbe/P1UMMoyDqleSvBqOWNKH8+YnXDz" +
            "qZPwyp3g6+VlMG33r7EgiA8N9E7gD2zPnrRh1aZlKn/23s+cst6OmiY9hqH9oiodlKcTU+rU2EotcczS+EWb62QZ42VHsj5eh8+chu/ctvxF5yL/ahQep+Kv" +
            "D2wXOn8ge/Zgq7jGveV9CRmXvX/Gcv+K7qcwZHkspJ+/LfXalR4vxrqFvzgNl47A00eXZw+AsM2/EoXmRPzoQDbl9Qetc0OQ9uwZ85a/8mtsL9O/Opme1i7d" +
            "lUYtk0ostc18L5RntcdUudTpvzpp7YHl2F7Z3rLVvxylFCeiRweuFaMexJ49y1V+ckGzT55o5rwkPQSX2hWq0GuEuI9XuyAVXT6LWcar232TVCmmYzt3iayM" +
            "L2+Ldxk7gmvSQnu3A5Qq/3Wj/bUt6RQw0wo+e8ZmetaWQH1WMuA0yOOVjWlSKvTwrJ3DW7c053Q5IJj0dqPQaRc6zwEg37Pn2hpUV0L5UwP6oVn4+9P2i0zK" +
            "2ouXVXx1+FuvCWf9JMH18n6W8BlX4hklBcHfn7bHRV1bW649YEEw4V0CCo41HhqIVoz6Qq/8tbRh1Uoof8b7z8XwieOtuTllReXtoqymxyS1JN+fp4ckuF6i" +
            "taZDUp3pYbyVesb8z584bud0+b3iUhDoS7g4eJo9J+0C9x7yL3TPnmtHe2vf14+B98kTtr636PLsx5CUPprX0mfxCn10fKZH1+pKjFf2jJ6yc/nx4/DqS1bi" +
            "e7J5Q+PeDpTSHGkczB1rJOt/B8j37FlJ5c+25384C/un7Y4St2ln2M5wza+c+bboi4zSQo+flr9L4d9izyBKSiWLO5UsHs/IYgplWDyW6eMZi+OVPWOCpaj3" +
            "n7dRdL2kfKHyjs0LrRjTbp0XYifwL1TPnquW2LOnk/I/Ng+fOmFBFefP4c0pcpF3l647hd8XU4byCqPyTW0LvxMpeTZp39RWWHwgdjF3J/+74phGFhfttLt3" +
            "u2df1HkuvW+QukYvq9pTNM0yV88MBKN6K7vCfTwZHcBIsuZNefVa9+yZTDs39NOzpxfaM2/gT442D6w2hULzlgioNOe4E3cv675myjq3tVnp6VI2WTYelHSF" +
            "K+s8VzLmovGkzb07PKNp84yZzfaRo3A+WRnTNaM9Nb2FXcE+9AXoR6rXcuXfmjas8laI9uQB8InjcCit7op76JyQlFRPFVOVSylTGYXooQLM0FqZVTRUO9G0" +
            "RNpTuW7PWPa3rkUzZQ13sXN7uG7tgZVj7PZOI3qSXeG+tOfQ2nWh89dq5b8obVilWBnak6c+/3LOHl804tmxeurB3+1AuxKuqwr1xD3nz3dwORppPxfFQ7d7" +
            "1biOzyjlGbe9PKNJ7YG7z9nv8tkTy40PLAZB1nEilvqadJzw10L5d4SWN6oVLpDR2C7Of3rcZi8uGKZFhSuJLilVrvhlmpNdawrxBNVGsUrHy8cDCjn6ps1n" +
            "M4UWLUp1AFWvz9jls+Ujv7pAU7P5DRTcdaxZl21WhErYia+osbQL3b1EUsfDW9Vi+1UriVw4tG2JbUt6cd0lAu89BI/OWa+Pkf6jq9368vRz2qMq0THVR61M" +
            "O6O01/FW6hl74c11sQGy/3ipzd9ixUiL/ZQNmeVwYz8NmVvVnUCvZueGS1ZB+fNf6ieO2+qurKHVojbmtAaGpEtpYbGLQrsgU2l5ZG6M/HimrK0h5Z+tjN8n" +
            "7dom5sdb4Wc0HZ5xoatEmirxsWMrzdazVoy1tAvdOGYVWzGuyg5gsLW7l1VXpzZYYzu5/dYhm0O0Xk+4YQj6M80b+PHdcPOmlaJCrTtBLHWeiPbTMLOrcnzf" +
            "/wfglB3SxddWQQAAAABJRU5ErkJggg=="

    }
}