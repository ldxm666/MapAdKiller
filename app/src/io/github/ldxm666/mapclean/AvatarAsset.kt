package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * The author avatar, embedded as data.
 *
 * <h3>Why not a drawable resource</h3>
 * The avatar started life in {@code res/drawable/avatar_author.jpg}, which works in a normal
 * build but not here. In an integrated LSPatch build the module APK is embedded at
 * {@code assets/lspatch/modules/} and loaded through its own class loader, and its resource
 * table is <b>not</b> visible to the host:
 *
 * <pre>
 *   host.getResources().getIdentifier("avatar_author", "drawable", hostPackage)   // 0
 *   host.getResources().getIdentifier("avatar_author", "drawable", modulePackage) // 0
 * </pre>
 *
 * Getting at the module's own {@code Resources} requires {@code AssetManager.addAssetPath}
 * on an APK whose path is only known to the framework. That was tried and abandoned: the
 * path is not reachable from module code (the code source points at the loader, and the
 * extraction directory is outside the sandbox's reach), so the lookup failed and the header
 * silently fell back -- first to 高德's icon, then to a monogram.
 *
 * <p>A 8 KB JPEG decoded from a literal has none of those failure modes: it depends only on
 * the class being loaded, which is the one thing guaranteed to have happened. The image is
 * pre-cropped to a square and re-encoded at 144px, which is ample for a 44dp badge at 3x.
 */
class AvatarAsset private constructor() {

    companion object {
        @Volatile
        private var bitmap: Bitmap? = null

        /** Decoded once and shared; every panel in the process reuses the same bitmap. */
        @JvmStatic
        fun drawable(c: Context): Drawable? {
            var b = bitmap
            if (b == null) {
                synchronized(AvatarAsset::class.java) {
                    b = bitmap
                    if (b == null) {
                        try {
                            val raw = Base64.decode(BASE64, Base64.DEFAULT)
                            b = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        } catch (ignored: Throwable) {
                        }
                        bitmap = b
                    }
                }
            }
            val decoded: Bitmap = b ?: return null
            return BitmapDrawable(c.resources, decoded)
        }

        /** 144x144 JPEG, quality 88. Regenerate with tools/embed_avatar.py. */
        private const val BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAQDAwMDAgQDAwMEBAQFBgoGBgUFBgwICQcKDgwPDg4MDQ0PERYTDxAVEQ0NExoTFRcY" +
            "GRkZDxIbHRsYHRYYGRj/2wBDAQQEBAYFBgsGBgsYEA0QGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY" +
            "GBgYGBgYGBj/wAARCACQAJADASIAAhEBAxEB/8QAHQAAAQQDAQEAAAAAAAAAAAAABgQFBwgCAwkBAP/EAEgQAAIBAwIDBAcEBwQH" +
            "CQAAAAECAwQFEQAGEiExBxNBUQgUIjJhcYEVQlKRI2KCkqGxwRYkM3IJF0NzssLxNVNjorPR0uHi/8QAGQEAAgMBAAAAAAAAAAAA" +
            "AAAAAQIAAwQF/8QAJREAAgICAwABBAMBAAAAAAAAAAECEQMhBBIxQQUTIlEjMmGB/9oADAMBAAIRAxEAPwAf9DJIz6UEU8gz3Fnr" +
            "JB8P8MfyJ10RoUZrVE7Ah3HeEHzbn/XXPT0L1U+khUO/upY6r+MkKj+euiBbAAB5AaLJFHhBBxjXmsuI+esdQcR0DF6mujbmFqiP" +
            "oUQ/1037RqnrNl0Mkr8UsatTyH9aN2jP8U0tpGVLzcI1Zi/FFKQRgDKcIwfH3NDWwK1TWbssuSGtd+nj4T+GVUqFI+GZW/I6JAzx" +
            "putp4bhdYG+5VCQZ8njRv58WnHJJ0iQGHccuB7NRTq2f1kYj+Tr+WgRi7TJfrmLRWWqslYLTTVa0MzHoveghD8P0gQftaeiSTnJ0" +
            "P73t0V17P7rSTBivc977PvAoQ+R8fZ5aKWwrY4wo8O4Z29nu6mJXALAHjTKty6+6U/LTiPeHzGhX1yo+wqS4Vjhq21TqlYyj3kI4" +
            "XcDyZHWX/ppyr9yUFDXeoQx1Nxr1IzSUEfeuv+c8lT9ojQaAJNoSpX9nVtCvxBqYwkjzBZD/AC0Hdj9cDaKeEHhir6T1mMHllopW" +
            "jc/MqYz9NauzGbeT9l6CJbTa4YKqubvKrjq5gpqZZQOBCiDCuB7x6aHNoWm4WrZO0q2r3DcEEKU/erTpFAI0nHC+DwFuRlU8z93V" +
            "c5U0X4odoyJrrpBFc7YwAPeTtDnyzE5/mg04KVHvLnOhC8277MqbLUi6XSY/a0CFKiqMini4l6EfHRaOmrCg1UssCyzwInBwS88n" +
            "qWAbI+fEdKPZC4Vcc9Nbd2L9MHOFZIJR8w7L/wDHS1Hb16aI9AqMv1yD/LUJRzq9DqqaL0oaalBIWpttQGI8kKSfzQa6N65z+hxE" +
            "jekRWVkhH91sNS4z5tLCn/NrotxDvnTyx/HP/tqMWHhlrCORZRxKeWSv5HB/lrInly0go2MsNZDxMhSplTiU8xk8QI/e1EOJqec/" +
            "2oVyF/vVD4fiikIP/q6HbUPsrtmvSsAsV3YEEeMscMbL+a97+7rbV2+4UtwoalNw1iLFcHpTxwwvwrMDjnwDPtGProX3NYL5/ait" +
            "lh3beGqqeOmr4Fgjp4jIwEiFciLxCFRj8fPSzlSsfHHs+pLn3SQCQPLSGC42yuuLwUtZFUT0w/SCJuLu+IdGI5AnGcdfhoapNubZ" +
            "vFKtRW1l0u0JijqUNxuErRtG65DFAVXHIggjkRp22p9mRWI0Fp9UFLSytFGaXh7tkzxIw4eRyrDn8DpxB91rnhWopJadxlZUaM/I" +
            "gj+utVFVpWUYlHJgzRyL+F1JVh+Y/lpTnHPUIMFVAlDTU9xmRXp2plpbgv4o8Y7z9kk5/VZvIa22n1Pb+1+6qmpqOGiykspKxI/D" +
            "0ck8ssvCST1JOllvnSoo54jwsYZpYHUjPRjyPzUj89RHcb7HZaXdl0utrS409nlrLdbZEUSTQFYu9jjIPTqwV/ALg4xkxgFVj3Va" +
            "qTsK3PVxVRjSmgldqmWNo4Q8lPHjDsAp9pxnB5aCdy9vXYxb9pR7fpd5UlbWf9nNHSK7ogJEYlMnDw8CAcec9F5ZyNB8Y35v3sav" +
            "mw62mtUO77/dEp2illMdPSU6U8MncM5yTwpH7WBliPHwqvfbRLR1NdaqerjlqaHDTxwxw93ImcF4mQk4BIyrENggnBBApnDyzTjt" +
            "t9TopurtAt8/ZjsLdkNfTNSXG8W+WSdXBUphmlK/iwQeQ5nljUrpIHiVwGAYA4ZeEj5jwPw1Rjs4qf8AWB2LbLtN1nnjXZs81xgE" +
            "Ps8MsVSrRBh4rwswwMchyIxq7a1FQa90UwyLxEshbhdB1yPBhzHl11d4ihpp0xHeW4K6HBA4o155/DPEf+bTmWK3cLgYaAnPyf8A" +
            "/WgLtWulTZ7Zba6lGZBKVC/i/SwNj8kOjqeZlvFNGoUpIsvtY5jHCRg+XX+GhYpzi9FmsFH2sXtu84GksjKD8BWUrH+AOukfEftS" +
            "ZSOXArA/tMNcp+x29/Yfa9b3Z1SOsimonLHA9tcrn9pE11YBV5xMp99Mj5E5/roiw8Num+mliS919KqMrkxzklsh+JeHIHhju8aW" +
            "RuGUkHoxB+hxoN3Le/sLtM280nKnuEMtPI/gCkkZGT8pXP7J1BzHtEauGyNxwWwE3Bbf9pUS/imgYOB+aR/noM7S+1TZ21aLbm77" +
            "762ll3FQyU8c8MRkKuAJUjdV5jIeQZHRlOcddEvapdrlaLZabhbKemqBJXNaqhJywAWoQx55EdGAPMjVSu0V901XojfY+8FgmulJ" +
            "cUv9NPAOERwyyOkigeGO9BwOQ4h56WUeyoaDcXaCXY/blt5N5yRXHZjUFuhri0MdY/fuIJCRKAHOFVZOBwOeON+eBys3eL5Dt2/W" +
            "jclMve2S6qttn7rCrHMSTSvz5KGYvCc4wzx56aoNuDc27tx9mNsNytdlgns1ijt9AtPSI8rUpdTJOepWfCL+zxnA4mzIGzu1m9x9" +
            "jdTS7po/XtvVVMYa2Fhh4yGKFkU4DYKcYIIZSPvBeQjJNUbZ/T89/wBd1f8AwuVQXWni3kIF40pLzE00QkUoyVMIAljZSMhjHwNj" +
            "/wAN9Pdsroq6iZkkDyQyvTzAfdkRirA/wP1Gq5V+7d0XDs2orNuJLhRXm21cVXbb6g4ZpTDhuIKwIdjGzBl6MGPUZIeLL2hXmw9p" +
            "9xmucELUdwSnqKmGBvYMhUR+sRZ+5KBGQemTwnBIwzZhUW1ZI1v3HRW3ti3BZaypSCGRYakvKwVVYxAnmfNQf3dRDd0lvVVT0pZy" +
            "L9LT3FICvJmmqZgzN8oin7uPE6178uMV87YrpPZpWlhudqo6WFWQq3rAd4+h8cTL+6dS7T7Gmh7VLDXmmH2XbbRJEWJBBmDcEaY+" +
            "CO5+g0k5U0NGlF2Va7drVEe1KqtkkFZ9nrdH9Tqi6pBJWCCASrIeuVjYMPD2mz7p17tX0Y91XLfKbieJaG33KnaZpAUdY+9A4ioy" +
            "M55sAQMcWD0ybh752HtrtD2bPtrctEZaSRhKkkLd3LBKPdljce64yefjkg5BI1GkfZz2wbdskVitna/RCx0sQhirJrI81wij6ABU" +
            "k7t2A5BuH6aqm29xOnxvqEceCOPqlKLu6BbsQ2jb7ae1q1itlrjCUt8yiHhp4njilP6J2JZmPEGbkAuVALddT3QzvUT2qbABktve" +
            "HHme6P8AU6YdmbRse0OzSfblvjrJI5RPUVVTcXBqq2eTiMk0xH3mIHLlgADAxjT1t5B9m2tmcGSG008Tp4qWVTk/u6si/g53JyvL" +
            "lllfy7BbtSh9drtqUIb/ABa6pJX8SrSSN/Ph0VUNW1VDtupPWelLn6wKdMG/Kukob5a62pCcVLTVMkXGcZkcxRqg+Le0Pz8jrbse" +
            "olqtlbIkqAvfG1lpOAYUMIkQ4+p1F/ZiOui/ezk888tPiogkMc0ftoynBUjmCPqNddtuXf7V21ti68RC3K3RygeZaBJP6Nrk5HTL" +
            "U7LrpkAL0tbA0nIZEciSJn5cYUfNh5jXR/sr3GtT6LnZfuCWT2Y1oaSZyemS1Gcn/Mw1YyiBL8IKyTqfGQsPkQP651F/bwJKXZFD" +
            "fYVJe3VEkvLy7liR9QhH11KDyCOoKvkEoD/HH9Rod7QaBLl2f1tK8fGDw5UDnzPDy+PtHUTotBi9NFvTs9etpJnlFTQRXykjQ+zJ" +
            "NFjjB8/dHLzbPgNDtPsaydsHo3QW+jqhTXH1eanFTNGeTNlcOOpRl4SCPDBHTQ/2X7tqrVsSKlrgDXWCOnu7wICRNRyqqVfdjqVU" +
            "kyY8DgH4yZ2fyUW3b/VbJVmWOmLyW2QvxJUUrsZo+A+PCrlcHwTI5Zwk50hoxe6Kr7e29RdkPabDZ+3a2LBbRFwQXWOOR6WpIxwv" +
            "HIuAD1DA4YYPLnqZLzcOyu7dn9X2b9lUNv3PWbhKRzUVDVGULCzDjeeYcRgjRcnPIjGFHERqf6n1aRJaarRaqOQjMMyKyDljGCMe" +
            "BPP46RUdpssLIKO2wUsQfjEFPGIY2bqGZUADEdeecfPWeUU/GdHkfU8/IUfuvaVe615o3XDa9iutn+y7hb4qil4EjCPzwEGFIPUE" +
            "eB66ao+zjaotVLb6i3rUxUkD0kDSks4gcYaJmJJZTgdfJfEA6KwcjXun9OcpNaTIzsfY7RWjekF6lvE9ZFRTrPRxSIONcJIvDI2f" +
            "bA7xSDgHKDOeupMxjXo669PTREvZg3TSKpEzcosAnq2emlpGRjWBTn0/hoNWWRdDJXQLS2GuqX/2dNK2BzxhCfr0H8fPTbsiqFdR" +
            "XKsHOM1vqcLk++kEUcWfq6yH66U77p7hWbBuFttVRDTVlagp0nm92FWI7yQ/5U4z5ZAzy0Odl9xguXZ9t6uogIqOvqK6pi/3IkkW" +
            "Mn4leAnRx60Se1YEdqV0es7aIqBZGMNvtyYQH2VlkfiZvnwlR8AT5nR92a8T7L2yxU8MVqkOfi04AH5KdQreLkt63xXXpTn1vMqk" +
            "/gaWTux+4ifnqdNi1NDQdk1trZpwsNLQZmc8+BVLOf4HWucaiiujlTZ6+poKuV6cxkyxNGY5UDpKMZKOp5MpAIx8QRggEXY7ErgN" +
            "2+gBue02Snamq7Q1b6rB3plMcqBayLDEZxx9M5I6ZPXVGkYidGjJBQ8QI8COedXA9BvcFI+4N4bSkeMR3CCG4rTsfdKExSgDxBWR" +
            "D8hg9NVlUfS19q3DTbr7PbJu63EGnutHHUoVOcCaMMB9H4RogeOKvowjjMcwUn5Eg6r96Ml69Z7PN09ktVNw3HaF1qaGFWPtCnMz" +
            "mFvkrqy/Qam7a1xW6bWgnA4XjLwSJ+B0cqR9MDQLb0Vst7y2jdFNUxx95Pb4zEYlOO9jDPBPD8OJeXwYIfDWu0bzvG3twUrVKi80" +
            "FtZ4qealhIlanAWWPC5xkCQSJjkOKVDhTy2b0mXaPazeo7jJiknuzyRScH+ElSVYZx90TYyfATL5aHY0raTclR6ovHHSz949OD7X" +
            "dnLCSPzHDK6sv+Vh1INjgp7DGTTtFnthbxs3aHtc3i2O3EjtDUQsOFkbPXGTgMACPhkdQdGKRhc8Kgc86rHQ7lr9lX+n3PRK08Cf" +
            "oa6lBCipgY56nkGVsMrHpkg8idWVtV1oL1bErrfP3kTEqQRwsjDqjqeasPEHmNZp4+rG7i0a919r7SoU0tTq1clUZZgyoUCCQhDk" +
            "5yV6E/HW7Oma5bjpqC+0lkgpKmuuNSpkWCAACOMZzJI7EKq5BA6knoDpZQ1lTUNLFWW6ajlRjyLB0ZcnDK45HI6jqPyJJBbr7X2m" +
            "Dc240slItPSrHUXOoBFPTseXxkfHMRr4nxOFHM6gYxcnSI97etxTDYtZtC1VEkM1wjWCvqYThqeGXKhFPg8mCPggc/hyxw3uHbHo" +
            "5bchpWCTVMNXbKPh+67s5JGPwoHb9kaYu0jjS00FF3klXUPco66qnfHHPLwvwZx0LMpAUdFQAcgNaquiu9w9E+2Udmgaru1tu60g" +
            "ES5ZTKzRFwvjjvQwHjjV2BJw7P8AZdmxLE+vz8gxSSjuI6mCJn74KkCDq4VjEij5leXz1M2ylnl7L7BYakAMKeKvurZ9lcgPHBnz" +
            "YBGI8EXn740K7T7Pqvua+33OZKKqpaaKnpHp2Er0STI3eHJ9lpgnINgqvG2OLqZZ2/baalqUtNNFwwLiQKTxEr94sT1ORzJ65Grs" +
            "sr8KGzmDsrs63HvOoaW30E8VphHHV3SSMiGJPg333OQFRckkgcuupX9EyiNs7Xr3uiVZEjscCxS+10jllKSg45EiNWb5pqxNabrS" +
            "7Uq907uq6CGjslLLXR2i3RMkKTxoSjSuxJfhOGVQFXPCxDEDEbeihYzQdldfdq6DE18qTUIW5iSGPMX/ABmT6EaqYqhTGvtH3TU9" +
            "gv8ApAKvd0Mcj2a9wxVVdBCP8enmUJMV82WSIyD4jHjq2u1KqiN8q6u0VcVZZNwRrerfUwnMbMVVZgD8f0cg/wAz+R1Ub0q7fTXP" +
            "a1lqROj3fbIFNMjH9JLbZ2xBL+sI5YzExHQspPvDUf8AYZ22722ZdLbsu2xJdqGpuCNQ0U8oQwTueErG7HASUMVZTyywYEEc5QPC" +
            "13bfaGO4hW0zJFVGmSthkkJC/o3VJM454XFO/LoQDpP/AKv5txvVXu10f9mrtA6JLbqhVmppZAnE4jdG5ROHXhK5ABB4QRjRJvq6" +
            "WTdO1LBusrUQw2+v7m4U7jgqKSOdDDIHUjqrcGQRg4I0/bMt0tq229DPckuMkFVJD60sfd94qYRMrk9FVRnJzjPjprCQhRwXP1y4" +
            "WKrs1bMtKp40C97LHGThkljGW9nIHGoZSpRvHJlXYVabntWOttleaW80QFBVS8JKzmMAR99GccYKFDnkw5gEdNOW76vZFiq6DcW6" +
            "6ult9bSt/c6nvGSpbPLu0VPblU5xwYYHPTSue41VVcqOheQ2z11ZHi48SVEiooZsDmseAw5ksefQHRk+3oULLR2hyF5qPc9gqrXW" +
            "0xxM1KTVwspzwyoVAfu2wcEpywQeYOiej3BY7hTvNQ3ajnVFLuI5QWQAZJK9R9RqLjC0u7pA80jBaVwx70lkcyBTg55ZEanHTIz5" +
            "6QXqiepmprdK8dVLK+UqJ4FMsIU5yGA54Iz0HukHOdY5y6y6m6HEU8XdPZI21RLdb1ctz1KFBMwpqVGPNIl5k/U4/d+OizUR2+nm" +
            "tUMNPa7tdKNYkAWOOqZlI8SUfiUnzONOM9yv9VAaaa/1Pct73dRRxSMPLvFAIHywfiNIsqDL6fkT1QSbg3YtDM9ttEaVdyHJ+Inu" +
            "qbPQyEePkg9o/qjnoIdVolqLhXVM1XVTsvfVDjikmYnhVVUcgMnCovIZ+JJULFSUNMkYaGmhXkoJCqPPr/E6ysNN9qyw3+Y5gXPq" +
            "UGR7PUGV/wBcg4C/dU/iY4ELyuvg09IcOHb2TGy47WnmqNsNU91LKt+irq9kyVwsUnCq/qqRGo+GT4nQdtmnuls7PN0NCKiSnqqm" +
            "aAmKMyNSVNM6mKbgXnwFgVbGSOFSRjJE08BPXlpPbbRSWw1K0iGNamrkrJPa6SSEFyPIEjP1OugtKkcpycm2xl2duGz7npKq8W6R" +
            "BUzNGKun41LQSLGF4cA5x1wT16jR7aabumjqyhLljEeXRSM/zA0PbRp1qoo6p4UVaqqmlkIUAuSS6ZPj7PDo0lVkop2iJVslxj4f" +
            "9NK2VtlVvS2eTanZq21tt/32v3Qjo9KFJnSGmXvpZgV5cIReBsgcmznljUhbD27bKWx7L29a0VaSO0QxSSqozkU6OxQ/rNzPxz5a" +
            "Z+zvbG79w36q332gxUlZvO6UnqQpoo/7pYqFiSaZMk5d85kbJzyUE4OpBs9DFtDdO3do8KrTxCUUVQx5zR90QIz+tGFVcfh4T56U" +
            "G0Vg9Mva1Ta6GxXxDiSCWW2SyLy7yGZeNQfhxRty89VEp6iopKyKrpZDHUQussTg81dSCp+hAOr3ekFurae/+0Ku7IIbjG1xgpmk" +
            "kbHspOIz3cQbxdePjYeAXHXIFFZaCviuzWtqV1r1m9X7gjn3vFw8P73LT9WkpDSi6UjoNXUknaTtO07nsEEbT3y0RVdVTiTgEj8K" +
            "ksWyAjK3Cv6xwOXDxA82fboG2bSp6xe4lReA0dZO0U1Iw96F+HHFwnIDHmV4eZ5Ev3ZdsGm2b2WWzbbAt6pSxUgY9f0YOT9ZGlf9" +
            "vTybbUioeIKGKYOM44lz1Gh2FspTuenirfSyq6SmSRY0vaQJ3jvIQIwvFzck4JRjjOOedWuNqetvVkvBCRGghlRSH4jIksSqRjAA" +
            "5qhzk9CPHVarTt29v6SNu3JXUrvaLtf5zBcVcNG3G0yqueoOBgA+XLVqKaOWGhhXhHEsah1zyyAAca18qvwS/Rpz66r/AAGxRQwb" +
            "xu8kRbMggZ8nOGYMTjyHu8vnpNbVqamurKyqSWNRM0VPHJkcKDALAeHEV+v108MA99uA4EVwYicLgkGMYyfHoR8hrIx64uV/mzqc" +
            "dfxxsTmPJB8R0PlrUaeoIIFYVHwiXI+v/wBaW93r0RjOqqNXcSwUcEcyNwcb8QBkk9pjz8z/AE0H2DcsNh3NZLRJRd1SXKi72rqu" +
            "PCJK0zx07Y/WSPgZv935HRlXO8FvneEZkC8Mfxc+yv8A5iNId77csk+wmjqbfFM9HClJSze0rR8bJF1UgleYJUnBxzGtfFW2c3nz" +
            "7dYhgF8+XwOtVzSoj21W1UUbkiMxxBeryP7ChfjlhoUs9xvFkihppqgXymgAQCtxHOVHTMqjDnH4l5+J8daL92rzy32gts+yb5RU" +
            "lPVCd6uTu3hlCq3AFaIuF9sofax7ut0sM7pIxS4+ROqJBsDKKMrSgSwwSwtEVP8As2jC5H056ITLCtLPJM4WJOLjZjgADroD2puO" +
            "0y7jq7b3VVRRVyq1KKlVVWJLlo0dSVJGSQM5wenLTvLNUjYdyhqHEtQZpKRy3jxOEyfgVPF9dUtNOmZ5RadMJ6akpqOAQ0sEcMY+" +
            "6i4Gkt3tkNxpUYxwetUz9/STSxh+4lCkBwPkxBx1BI8dOGvtABx73XDubbnateYb5UzR7iornM1TUKx4vWBIWMik88EniB8iNSv2" +
            "ZUVD2q9v+yb96vDFdYbrTtfKNRwrOsftpUoPImNVdfA8J6HRX6cezIbL2xWjeFKgRL9Rsk+PGenKrxfWN4x+xqPfRYmEXpbbODMV" +
            "V5ahOvI5ppcD88aZPVDqWmjqGowo5a01UbvTsYgveAHhz4/DPx1vHTX2lEIequyT1xqMwxW7uqaqSqWXEiTGRWLcToG4DIMkcWpC" +
            "+x53g7xHXjyeJD4HPgdPLq0UvexgcLe+On119HIvfumRzAcfEaNsNsj26Us9Du+nMsTKKymaPzy8TcQx+zI37uvD7xBGD4jRPdhT" +
            "XelirKSQNPaq4SMAOalDwyJ9UZvzB04VVuo61P00SliOUi8m/PWXJC5WbsHL6RUZIB+vTWcUUs0oihjZ3P3VGdOMNkH9qKiiWoYw" +
            "JCkrNjmCSwx88KNPFXJS2eh7qkEMU0ikq0pwoA955D+Fep+gHM6rUGzRl5sYr8dgra6Ge674FvaJlpbbwz1jn/vSMxRA+eD3jeQ7" +
            "v8Wnvf1HTL2d1SFWSKGWCc92Bn2J0bn8OXP4Z067dlspoHhs9wpq0hzJPLFIrM8jnJd8eJP8OQ5DSfeEElz27Nt+nl7qa5I9OZQM" +
            "9zGV9uTHwHT4lda8UelHOnmc592RvM8dOhkmdUUHBZjgZJxrIZVsgkEaYbhdYktNHUXJ46Zo6+KnrElYARSZKMreQ4yvM+DKfHTf" +
            "Yr4NyW+qt9mq3WOjrZKOWsjPFiNG9kIx5FmUgZ+6MnrjPbW9o7Ckn4Es9RRU9xtiV1VDSxyV0LGWaUIFEbd8xyT1xGfz0ZVV5t9w" +
            "rno7fNFWUd0Q1RkjOVZUUxOPnxKoP11ElvvtdubeMtZboEG3KNDFFXPzFXNn2zCPwLgL3nQ+0FznOjnbaIwtU3WRaa4QyH9YVqsf" +
            "+LP11j5UPJGHlwupkvEhRknA89YRzwykiKVHx14WBxqKblerhdZCamobu/CFDhB9PH66R0NVUW2uSrppBG6HOfAjxB8xrHRj+2yO" +
            "/TBsMG86ra+3lnMFTTQ1VXHMeaq7GNVVh5MEk+Ps58NVC2JNcezD0g9q3S/U0lJ6hdYJZSfdeEvwOyt0ZeFm5jV0+0uRazY9Ruq6" +
            "Ax1k1bBKg4cFI+caJjwHduzH4ltRDuSw0d/2bcbdX0kVRmCUxB1yY34ThlPgcgcxq/BjWXG2vUXzwOMV+y+KkFQVOR4HXuoR9Fzt" +
            "Uh7S+w2hgraoPf7JGlBcUY+2/CuI5viHUDn+JWHhqbgQRkHOs5kPtM1xZ7dPT1oJ9Xibhf8AVjbAI+h4WHyI8tPOsJYo54GilRXR" +
            "xwsrDII8tQgNQ0VZae0Weui9u2XdB3x4gO6qUVVQ48QyAjI8VAOcjDlR1Ur1NVRVLZngkznGOKNslG/LKn4qdabbBVUiyWStVpKe" +
            "MD1SpJyWTwVj+NMYz4jhPXOtG5I7lQ0Av9ro3r6+iiYyUcPJqyMDJjXPLjyAVzyzy6MTpZLQUzZ38ND67cGYzNUThI44+bOQAgQf" +
            "HIb4dT4HTPvHa027Ns1NmWQUdTVwp30yguodGDIp5jMYIJIGM/XWzs7irrhsqzXu8QNDUy0UbJTyHLRcahnLfrsSc+QwOvFkqqqq" +
            "GhiDycTO5wkaDLyN5KPH+mhCNEbsi7YPZ/uTaW42r7tWUK0UKsI4qSSSQylhjADKOBc4JXJyyqRjmSb3S70tho5rzdifWJV7qnpl" +
            "5u3UrGgHVieZPQdTyXOlFdVw2m3VO4L1UGKKKMN3XURdBgAe8xJAB8cgDUS190qa+tmvl6nVJFRjwgkpSxDnwL8gMs3ViPIADVhw" +
            "vNIv4/HeZ/4hjWbbN8kqabcEFqmuNTK1TV0NdFGrqz45BH5lAFVQwyCFBydCu/L1TbD2fb7TspqRXnrxm307iTEOGeRVVTxIpKgE" +
            "jPvEDGdNtb2ezdoe4Ky6b0aaheaAC0U8fCzUkIY5EikYLnKOwB+9jPLk423YO3LRtrbNzSzW+lrrTWRirqIIhmccRgkLE82UsVfB" +
            "6Y10+nVKjquLUaQ5bZ7QkvcYgr9t1VmZFCg8cZgAxgAHiBUfDHLT+u/7HtWnneqZqmp7irq6SGLkk6mdU5zH9HGpeE+0xAwCRnGN" +
            "OdLHT2ep7x7fBWUIyHpZKdJpI/1oiwyPimSCPdweRpn29b9j7Qu2O6z26RvsCkKUNDCrFYnSEFe8EYwBxMXIyM4I1l5EnKotUjLy" +
            "JSdQkj//2Q=="

    }
}