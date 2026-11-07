package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * 赞赏收款码，以 base64 内嵌在源码里。
 *
 * <h3>为什么不用 drawable 资源</h3>
 * 本工程是手动 aapt2 + d8 构建，<b>没有生成 R 类</b>；且模块跑在宿主进程里时
 * `getIdentifier("donate_qr", "drawable", pkg)` 返回 0（模块资源表对宿主不可见）。
 * 一条字面量里的 JPEG 没有这些失败模式：它只依赖「类被加载」这一件事。
 *
 * <p>原图 1037x1037 / 67506 B，重新编码为 600x600 / 32331 B（quality 85），
 * base64 长 43108 字符。600px 对扫码绰绰有余。重新生成见
 * `.agent_work/scripts/mak_embed_donate.ps1`。
 */
class DonateAsset private constructor() {

    companion object {

        /** 600x600 JPEG, quality 85. 重新生成：mak_embed_donate.ps1。 */
        private const val BASE64 =
                "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAUEBAQEAwUEBAQGBQUGCA0ICAcHCBALDAkNExAUExIQEhIUFx0ZFBYcFhISGiMa" +
                "HB4fISEhFBkkJyQgJh0gISD/2wBDAQUGBggHCA8ICA8gFRIVICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg" +
                "ICAgICAgICAgICAgICD/wAARCAJYAlgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
                "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
                "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
                "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
                "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
                "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
                "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5Zoor1b4T/CeHx9bXuq6rfy2mmWsvkKtu" +
                "B5kr4BPJyAACO3egDymivRvir8Mx8PdRsms7573Tb8N5TSgCRGXG5WxweCDmuBtbaO4ZxJeRWwUcGXPzfTFAFeitL+zLX/oN" +
                "2X/j3+FH9m2v/Qbsv/Hv8KAM2itH+zbX/oN2X/j3+FL/AGba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8A" +
                "x7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+" +
                "Pf4Uf2ba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv" +
                "8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+Pf4Uf2ba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8A" +
                "j3+FH9m2v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgD" +
                "NorS/s21/wCg3Z/+Pf4Uf2ba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf" +
                "4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+Pf4Uf2ba/wDQbs//AB7/AAoA" +
                "zaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9" +
                "m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+Pf4Uf2ba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8Ax7/CgDNo" +
                "rS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+Pf4Uf2ba" +
                "/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL" +
                "+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21/wCg3Z/+Pf4Uf2ba/wDQbs//AB7/AAoAzaK0v7Ntf+g3Z/8Aj3+FH9m2" +
                "v/Qbs/8Ax7/CgDNorS/s21/6Ddn/AOPf4Uf2ba/9Buz/APHv8KAM2itL+zbX/oN2f/j3+FH9m2v/AEG7P/x7/CgDNorS/s21" +
                "/wCg3Z/+Pf4VVuraO3ZBHeQ3QYZJiz8v1zQBXor0b4VfDQfELUr1ry9ks9MsAvmvCAZHds7VXPA4BJNaHxY+E8PgG3s9V0q/" +
                "mu9MuZDAy3AHmRSYJHI4IIB7DpTsB5TRRRSAK9G+GnxVvvh6t3Ztp66lpl04laDzPLdHAxuVsHqOoIrzmrFtam5iupA4UW8f" +
                "mHI+9yBj9aAOx+I/xHv/AIh6pbTTWa2NlZhlt7ZX3lS2NzM3GScD8q4aikoAWiiigAoyaKKADJoyaKKADJoyaKKADJoyaKKA" +
                "DJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKA" +
                "DJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKADJoyaKKA" +
                "DJoyaKKADJoyaKKADJoyaKKADJoyaKKADJooooA7j4cfEe/+HmqXM0Nmt9ZXiqLi2ZthJXO1lbnBGT2q98S/irffEJbSzXTl" +
                "03TLVzKsHmeY7vjG5jgDgZwB6mvOasXFq1vDayFw32iPzAAPu84x+lFwK9FFFABWlpn/AB5at/16/wDswrNrS03/AI8tV/69" +
                "f/ZhQBm0UlLQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUU" +
                "UAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUU" +
                "UAFaWpf8eOlf9e3/ALMaza0tS/48dK/69v8A2Y0AZtFBooAK0tM/48tV/wCvX/2YVm1o6b/x56t/17f+zCgDOooPWigAoooo" +
                "AKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAoooo" +
                "AKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAB1rS1L/AI8tJ/69" +
                "v/ZjWbWlqf8Ax56T/wBev/sxoAzTRRRQAVo6b/x56t/17f8AswrOrR03/jz1b/r2/wDZhQBnHrRQetFABRRRQAUUUUAFFFFA" +
                "BRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFA" +
                "BRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAVpan/x56T/ANev/sxrNrS1P/jz" +
                "0n/r1/8AZjQBm0UUUAFaOm/8eerf9e3/ALMKzq0dN/489W/69v8A2YUAZx60UHrRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRR" +
                "QAUUUUAFFFFABRRV/RdH1DxD4gsNB0qJZb/UJlt7dGcIGdugJPA+tAFCivZf+GYPjP8A9C9Zf+DKL/Gj/hmD4z/9C9Zf+DKL" +
                "/GgDxqivZf8AhmD4z/8AQvWX/gyi/wAaP+GYPjP/ANC9Zf8Agyi/xoA8aor2X/hmD4z/APQvWX/gyi/xo/4Zg+M//QvWX/gy" +
                "i/xoA8aor2X/AIZg+M//AEL1l/4Mov8AGj/hmD4z/wDQvWX/AIMov8aAPGqK9l/4Zg+M/wD0L1l/4Mov8aP+GYPjP/0L1l/4" +
                "Mov8aAPGqK9l/wCGYPjP/wBC9Zf+DKL/ABo/4Zg+M/8A0L1l/wCDKL/GgDxqivZf+GYPjP8A9C9Zf+DKL/Gj/hmD4z/9C9Zf" +
                "+DKL/GgDxqivZf8AhmD4z/8AQvWX/gyi/wAaP+GYPjP/ANC9Zf8Agyi/xoA8aor2X/hmD4z/APQvWX/gyi/xo/4Zg+M//QvW" +
                "X/gyi/xoA8aor2X/AIZg+M//AEL1l/4Mov8AGj/hmD4z/wDQvWX/AIMov8aAPGqK9l/4Zg+M/wD0L1l/4Mov8aP+GYPjP/0L" +
                "1l/4Mov8aAPGqK9l/wCGYPjP/wBC9Zf+DKL/ABo/4Zg+M/8A0L1l/wCDKL/GgDxqivZf+GYPjP8A9C9Zf+DKL/Gj/hmD4z/9" +
                "C9Zf+DKL/GgDxqivZf8AhmD4z/8AQvWX/gyi/wAaP+GYPjP/ANC9Zf8Agyi/xoA8aor2X/hmD4z/APQvWX/gyi/xo/4Zg+M/" +
                "/QvWX/gyi/xoA8aor2X/AIZg+M//AEL1l/4MYv8AGvIr6zuNN1O7067UJc2kz28qg5CujFWGR15B5oAr0UUUAFFFFABRRRQA" +
                "UUUUAFFFFABRRRQAUUUUAFFFFABRRRQAVpan/wAeek/9ev8A7Maza0tT/wCPPSf+vX/2Y0AZtFFFABWjpv8Ax56t/wBe3/sw" +
                "rOrR03/jz1b/AK9v/ZhQBnHrRQetFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFdv8H/+S5+CP+wvB/6FXEV2" +
                "/wAH/wDkufgj/sLwf+hUAfp1RRRQAUUUUAFFFFABRRRQAUUUUAFFFeI/tGfFfxN8JvCui6p4bsLS4e9vTBM92jOqKF3bQARy" +
                "2Dz7UAe3UVieE/EEHivwZo3iS2ULFqdpHchQc7SyglfwOR+Fa88Sz20sDlgsilCVOCARjg9qAI7W+sr1XayvIbkRsUcwyB9r" +
                "DqDjofarFeZfCT4O6P8ACO11m30nV77URqtwszm7I/dhQcAY6n5jk9+K9NoAr3N9ZWbRLd3kFu0zbIxLIE8xvRc9T7CrFfE/" +
                "7YN3Pe/F7wJoVtM4kSASKqsfleScKCPf5BX2tGpSNULFioAJPegB1FUNauNQtNA1C60q0F5qENtJJbW7HAmkCkqme2TgVwfw" +
                "Z8S/EXxR4Nub74leGxoWpx3bxwxiJoTLFgEMUYkjkkZ74oA9LoooyPWgAooooAKKKKACiiigAooooAK/Krxl/wAlB8S/9hW7" +
                "/wDRz1+qtflV4y/5KD4l/wCwrd/+jnoAw6KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK0tT/489J/69f8A" +
                "2Y1m1pan/wAeek/9ev8A7MaAM2iiigArR03/AI89W/69v/ZhWdWjpv8Ax56t/wBe3/swoAzj1ooPWigAooooAKKKKACiiigA" +
                "ooooAKKKKACiiigAooooAKKKKACu3+D/APyXPwR/2F4P/Qq4iu3+D/8AyXPwR/2F4P8A0KgD9OqKKKACiiigAooooAKKK+fP" +
                "A/7R0XiD426x8NfEmhLocsV1LbadMZCTK6MRskBAwzAZBHHbuDQB9BnpXkunad8ZU/aJv9Rv9Stm+HLQFYLYMmQ2wbflxuDb" +
                "8knOMV61UF5d22n2Fxf3kyw21tG0ssjdERRkk/QA0AT1xfxM+HGh/FLwW/hjXZbiCEzJPFPbkCSKRc4IyCOhIx71Y8C/EXwl" +
                "8SNGn1bwjqRvra3mNvKWiaNkcDOCGAPIIINdNdQm4s5rcSNGZUZN69VyMZFAGN4N0DSvCng3TPC+i3DT2Wlwi2R3cO5x13Ec" +
                "Zya36+Jv2TNYv/DPxl8YfDnVLmR2lEjBXOczwSFWP1KsT74r7Z7UAeTfDn4T6r4G+Ini7xTe+MrrWoNekLx2kqsBDly2SSxB" +
                "IB2jAHFes18meG/iR40/4bl1fwdqOvXM2hyyz20dg7fuo1WHfGVXseOvfJr6zoA8T+InwGg8ffGXw34/n15raDSREJrEw7vO" +
                "8qQyLtbPy5JweDXtgrya58KfFR/2i7fxPb+Lo08Brb7ZNK8xsk+Xgr5eMEl/m35z2r1noKAPNvjN8RNZ+GngeHX9E8Ly+Ibi" +
                "S7S3aJCwWJSCd7bQTjgD6kV3Gh6hPqvh3TdTurKSwnu7aOeS1k+9CzKCUPuCcfhXl2s/HnRtJ+PunfCWPR7i7urpo4pb5JQF" +
                "gkddyrtxk8YycjGfavYqAGu6pGzuwRFGSzHAA9a+U/hl8YPHPxL/AGptTtNJvyfA1lHNm18tdgiX5Ek3YzvZ8Hr0JHavfPij" +
                "pXiPXfhT4j0bwm6prN7aNBbln2Z3YDDcehK7hn3rg/2cPhHe/CzwFcjXook8Q6pN5t0I3D+VGvEce4cHGSTjjLe1AHt1FFeT" +
                "eOPib4n8L/F/wp4N0vwVcatpms7ftOoIH/cbnKnBA2jaBuO7saAPWaKB0ooAKKKKACiiigAr8qvGX/JQvE3/AGFbv/0c9fqr" +
                "X5VeMv8AkoXib/sK3f8A6OegDDooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigArS1P8A489J/wCvX/2Y1m1p" +
                "an/x56T/ANev/sxoAzaKKKACtHTf+PPVv+vb/wBmFZ1aOm/8eerf9e3/ALMKAM49aKD1ooAKKKKACiiigAooooAKKKKACiii" +
                "gAooooAKKKKACiiigArt/g//AMlz8Ef9heD/ANCriK7f4P8A/Jc/BH/YXg/9CoA/TqiiigAooooAKCQBk0V4D8evjZq3gq/0" +
                "7wF4Csv7R8aawF8sBPM+zKxwpC/xOxBwDwAMntQB79Xk/in4D+DfFXxW0j4jXEl3Zarp8sc0qWzBY7toyChfjIIwASCMgAV4" +
                "vF8Af2hdWtxrOr/GOaz1aQeYbZbycqjf3SyEKP8AgIIrY+GHxe8e+EvibH8IPjUvnXtyRHp+qnB80t90MwwHVugbqDwfYA+i" +
                "fDvjPwr4slv4vDevWeqyafJ5N0tvJuMTc8H8jz04NbVzbwXdpNaXUKTQTIY5I3GVdSMEEehFeT2vh34U/s76Lrni3dNpdpqd" +
                "wguJZGedixLFI0UAnHLHH68V6XoWu6T4m0Cz13Q72O+069jEkM8fRl/HkHsQehoAzvCPgfwp4E0ybTfCeiwaVazymeRIiTvc" +
                "jGSSSegAro686+MUPxPn8Conwnnhh1z7ShkMhjDGHB3bTJ8uc7evbNdnoI1dfDmmrr7RNq4to/tjQ/cM20b9vtuzQB8U/ETP" +
                "ws/bm0vxKoaKw1W4gunI4BSYeVN+u419zjpXgX7Q/wADdW+LU2gaj4e1C0sdR00vDIbosA0TEEEFQeVIPHua910+CS10u1tZ" +
                "pvOkhiSNpD/GQACfxxQBw9xF8J9P+MdrPcjR7f4gX0GISxAuZU2kcDpnapHqQK9BrzzWvht8Pbz4s6R8QdWAi8TW6iK03XWx" +
                "ZioIU+Xn5mAbt7V6HQBgad4z8Kav4n1DwxpmvWd1rWnDN1ZxyZki5wcj2JAOOmea36+IP2ZA+t/tT+OPEDEsFivJd2c8yXK4" +
                "/TNfX/jLxhofgPwneeJ/Edy1vp1oF3lF3MxYgKqjuSTQB8efAu1ufiJ+2B4m8c3sBEOmyXN0Aw+4zMYYlPoQuf8AvmvuOuV8" +
                "B6z4S8VeFofFvhC0hhstWJlaRLZYZJGDFT5gA5YEHrmtDxYviF/Burr4TeFNeNrILFp8bBNt+XOeOvrxQB59rNj8Z5P2hNJv" +
                "dI1C1X4dLCouoGaPJO07gQRvLFsYI4xXrYrgfhJF8RYfh5bx/FGaKXxEJpCxjKE+Vn5AxT5c9enbFd9QBynxF1XxTonw71fV" +
                "PBWkrq2vQRhrW0ZSwc7gCdoILYGTgHnFM+HGq+Ktb+Hekan420hdI16eNjdWqqVCEMQp2kkrlQDjPGa609q+UfiD8WviJ8Rf" +
                "ihc/Cr4Jv9kWxZk1DWAQCCpw5D4OxFPy5HzMelAH1fwBRXx9P8B/2h/D1sdc0D4vzanqkA8z7I13MBIepUeYSrfRgBXpnwE+" +
                "Nd78Q49R8KeL7Mad4y0bIuYtnl+eobazbP4WU8MPcEdcAA91ooooAKKKKACvyq8Zf8lC8Tf9hW7/APRz1+qtflV4y/5KF4m/" +
                "7Ct3/wCjnoAw6KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK0tT/wCPPSf+vX/2Y1m1pan/AMeek/8AXr/7" +
                "MaAM2iiigArR03/jz1b/AK9v/ZhWdWjpv/Hnq3/Xt/7MKAM49aKD1ooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACii" +
                "igArt/g//wAlz8Ef9heD/wBCriK7f4P/APJc/BH/AGF4P/QqAP06ooooAKhubu2s4xJdXEUCEhQ0rhRk9Bk96mryb48fCOX4" +
                "u+B7fS7PVjp2oafMbm235MMrbdpVwORx0I6fjQB6xnI+tfIOnNFB/wAFGdQOuEK8luRYGboWNsu3bn23ge9ei/s66H8YPCul" +
                "avoXxNbOl2ZRdMaa4WaQYzvAYEny8Bcbunaq3xk+FenfGCK18Y/DjxHYp4u0NhHFdWlypWXadwjZ0J2OpyVPuQeOgB1Hiuz+" +
                "M0nxw8NXPhi+tY/AkaJ/aUTlMn5j5mQRuJK7du3oa8f/AGujbyeOPhnb6eAdd+1sYxH/AKzYZI9n/j4OPfNJD8Tv2sdGtRol" +
                "78MF1O+iHlC/+xs+89AxMb7D654rf+FHwR8Z3/xGb4t/Ge9F14gVhJZaeJAwgbHys235VC5+VF4B5PNAHtnxL8Bab8Sfh5qP" +
                "hTUz5YuF3wTjrBMvKP8Ageo7gmvO/wBm74dePfhp4X1rQ/GVzbtaNdiSwggm80IMESMD2DHacex6Zr3TNeR/tD6V411P4O30" +
                "ngPU7uy1KxlW7lSzcpLcQqDvRSvOeQ2B124oA9drltU+IXgzRfGWn+DtU8QW1rruogG2s3J3SZJC84wMkEDJGcV5z+zR8TNV" +
                "+I/wvf8At5ZJNU0aYWU12w/4+htBVz/tY4b3Ge9eZ/th+BrmCPQ/irou+G80+RLS7ljOCg3boZPbDZGfdaAPpH4j3eu2Hwt8" +
                "TX/hmQR6xbafNNavtB2uqk5APBOAce9eS/ssfE/WviF4D1S08T6kdQ1jSboAzyYDvC4yhOAM4IcZ9hXpXwu8aWnxL+FGkeI8" +
                "o8l3b+TexD+CZRtkU/jz9CK+U/2e5JPhx+1j4l+H90wjhvDcWcYPRmjbzIj+KA/nQBf/AGwZ7rTPi74C1aGZ18mDfFhvuuk4" +
                "Ykeh5X8q+042E8COPuuob8CK+O/24LIrF4J1ReNrXUOcdP8AVsP5Gvp218SWOk/Ci08W6nIy2VtpEd9OyjcdoiDHA7mgDlPh" +
                "Z8D/AA/8Ktf8QaxpOpXd7NrDDC3AUCCMMW2Ajryep9BXkv7a/iY2vgzw54Shk/eajdtdyqB/BEuB/wCPP+le6fC74peH/iv4" +
                "Xn1/QIbq3jt7hraaG6UB0YAEdCQQQQetVvEei/Cb4ieNLbRdfGla14i0A/aEszcfvrcHaTuVWGVztJByOnFAF34R+Gf+EP8A" +
                "g74Y8Psu2a3sUeYf9NX+d/8Ax5jXO+KD8aP+F6eHR4aFp/wgGxf7RL+XuzlvMzn58427dvGeta3xk+ISfDL4Van4lj8s34At" +
                "7CNxkPO/C5HcDliPRaw/2dvFvjXxx8JYvEXjd45rm4upRazrEIzLCpAyVUAfeDAHHQUAewDpxXk3xc0j4w6nq/hiT4Ya3bad" +
                "ZwXBOprKyqXXK4J3A7lA3ZUc81Z+Lfxj0r4RxaLJqejX2p/2tO0KC1wPLC4yTnqfmGB35r0yGRZoElUEB1DAMMEZGeRQAy48" +
                "77FMIv8AXeW23H97HH618o/sYvbJa+O7e7KjXRfxm4V/9bsww57437s+9fSviTxr4T8ICzPifxBZaT9tk8q3+0yBfMb2/wAe" +
                "gr55+JHwV8deGviRJ8V/gddImoXTNLe6ZvUCUtyxUN8rq3UqSOeR7AHpngGz+M8HxU8WT+Or60m8JyM39kxxFMj5/k2hRuAC" +
                "cHd3rx3SGtJv+Ci2pPoODElq4vjF93eLdQ+f+B7Qfei5+JH7WPiO0Og6f8NF0S7nHltqH2Vo/L4wWDSPsU988+1eo/Aj4JD4" +
                "X6beazrt4NT8XauM3t1uLLEud3lqx5bJ5Zj1IHpQB7USFUsxwBySe1RW91bXcImtbiKeIkgPE4ZcjryK8B/aM8NfGXxj/ZHh" +
                "z4dnZoN0jLqTR3KwEvngSEkN5eOy5zzkdK7X4I/Co/CT4f8A9gTas2pXlxObu5kAIiRyoXbGDztAUcnqeeOlAHp9FFFABX5V" +
                "eMv+SheJv+wrd/8Ao56/VU9q/Krxl/yULxN/2Fbv/wBHPQBh0UUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQA" +
                "Vpan/wAeek/9ev8A7Maza0tT/wCPPSf+vX/2Y0AZtFFFABWjpv8Ax56t/wBe3/swrOrR03/jz1b/AK9v/ZhQBnHrRQetFABR" +
                "RRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFdv8H/+S5+CP+wvB/6FXEV2/wAH/wDkufgj/sLwf+hUAfp1RRRQAUV5" +
                "N4n+HPjbWfjt4e8b6b45lsPD2nIi3GkhnAlwWLAKPlYMCASeRivT9R1Cy0nTLnU9SuY7WytY2mmnlbCxooyWJ9AKAHXtpDfW" +
                "FxZXClobmNopADglWBB57cGvhbxd8Kvih+zn4jl8bfDbU7jUPD4OZWRN5jTP3LiIcMv+2On+ya+1vDHizw74z0Rda8L6tBqm" +
                "ns7RieHOAw6gggEH6itlkV1ZHAZWGCpGQR6UAeJfB79ovwr8UIodKvSmh+JsANYyv8lwe5hY/e/3TyPfrXt/GeK+Xfi/+ypp" +
                "3iC6fxN8M5IdC1sP5r2WTHbzNnO5CP8AVPnnjj6da9u+FumeMdH+Gekab491BL/xBAjLcTK+/I3HYC38RC4BPegDmvEek/GO" +
                "f486BqOgaxbQ+AYol+32zMoLH5t4KkbiT8uCDx/P1gdKWigCG3s7SzjMdpaxW6MxYrEgQEnqcDvWX4s8N2HjDwdqvhjU1zaa" +
                "lbvbuR1XI4Ye4OCPcVtUUAfNv7L/AMPfiL8Ov+Er0jxZaLa6M9wrWYMgYyyjKtKoBOFKhevPA9KyPiR8IPG13+1n4a8feENN" +
                "VtOkltp727MiqsDRnbJuBOTmMDGAc19UkgUwSIW2g5NAHz/+1T8OvFHxC8CaJF4S0ttTvrC/Mjwq6o3lshUkbiAcHbXUeIPC" +
                "fiq6/ZVm8FxWsdx4kOgR2JgSQYaQIqsoY4HQEema9WeQqOBTElZmwVoHZnjH7M3w/wDEPw8+Es1h4msPsWp3t9JdG3LqxRdq" +
                "qoYrkA/KT1PWuG+BXwk8eaX8dvFXxF8d6c2nvK1wtuDMr+e8smSw2k/KFGBnHUV9SF9o5oWVW4zSuFj5k/aU+G3xK+J3jbwl" +
                "o+hWCyeGIs+fcrKo+zys2Hd1JBwEAxjPeveJo7fwB8M5I9E0qS8g0HTT9msoBh5hFHwo9zj9a6egjNMR5t8KPGtx8VfAEXiP" +
                "xB4SGjzx3bxpb3C+YpK4xIhZQe5GcdQa3PiP43svhz8PdV8X31rLdxWKDbBF1kdmCqM9hkjJ7CutxVe8srPUbOWzv7WG7tZV" +
                "2yQzIHRx6EHg0AfDXw8+HnjD9pL4gSfEb4iyyw+GYpMRxrlVnVTxBCD0QfxN3575I+6ooo4YUijQJGihVUdAB0FZ13daH4U8" +
                "Oy3d1Ja6Ro+nxbnYgRRQIPYcAUuh69pHiXRrfWdB1CDUdOuQTFcwNuR8HBx9CCKAPIfil+0t4H+Gmrf2GkU3iDWI2xcWtm4V" +
                "bb2dzxu/2Rk+uK9i0XUotZ0DT9YhikhivreO5SOUYdA6hgGHYjNea2v7PXwyt/iRfePLjSpdQ1K7na68m8k8y3ilY5LqmOue" +
                "ecgdq9YVQowOlAC4orm/Gnjjwx8P/Dj6/wCK9TSwsVYRqSpZpHIJCKo5JOD+VcD8KPj94e+LnifV9E0XRdQsxp8IuFnudu2V" +
                "NwXopO08jg+/pQB7FmivJfAvw58beGvi54p8V6546l1jRtWL/ZNOZnPk5cMuQflXavyjb1Br1qgAPavyq8Zf8lC8Tf8AYVu/" +
                "/Rz1+qp7V+VXjL/koXib/sK3f/o56AMOiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACtLU/+PPSf+vX/wBm" +
                "NZtaWp/8eek/9ev/ALMaAM2iiigArR03/jz1b/r2/wDZhWdWjpv/AB56t/17f+zCgDOPWig9TRQAUUUUAFFFFABRRRQAUUUU" +
                "AFFFFABRRRQAUUUUAFFFFABXb/B//kufgj/sLwf+hVxFdv8AB/8A5Ln4I/7C8H/oVAH6dUUV4p8efjD4k+Etpo91onhIazb3" +
                "jSfaLmUuIoNuMKSvQnJwT6UAe11n61o+n+INCvtD1a2W5sL6FoJ4mJAdGGCMjpXzX4Y/bR8D6iFi8UaDqOhy4GZYcXMWfwww" +
                "/I17Z4Y+Lfw28YBV8P8AjLTbqZsYgeYRS/TY+G/SgDR8D+A/DXw68NL4e8K2bWtgJWmKvIZGZ2xkljyegH4VyHi/4vy+FvjP" +
                "4Z+Ha+E72/TWwhOoRthYtzFeBg7tuMtyMA16ruFBVSQSoJHQ+lADTLGrpGzqHfO1SeTjrin15R42+DUfjL4u+F/iA3ie9086" +
                "Fs/0KJcrLtcvw2flznDcHIFel6rqVlo2j3mr6jOLeysoXuJ5SM7EUEsfyFAHyL8Ste+Pfwh+Ml54ut7m78SeEtSuSYrUbpbd" +
                "IyeISoH7pwOAw6+/Ir660q8fUdFsdQktZLR7qBJmgl+/EWUEqfcZx+FcF4H+OPwz+IN0bLw/4jiF+SQtndqYJpPdVb734Zr0" +
                "kUAFBPFNdwi5NQROzO24mgdgmLZAqpNeW1hGbi7mEadBnqT7DvUeqaktrBLJGN3lD5j2B7D3NcLc3dxeTj7ZIXeQHntGPQVE" +
                "pWNErnVzeKbRwDbxsyf3m4z9Kz38T3gl3QxxFD2YVz5t1LYikdOcKgPH5U9/OhjxLDvT++o5/GsueTHynV2fieKciO8g8nPA" +
                "dDlfx7it2Iq2GRgy+oOa86SAtGJojnNXLDUbuwuI5o23RA/vIieHHqPQiqjPuFj0F32R7vSnI4cAiq7SpNaLNE25GXcCKahZ" +
                "WAU9K1uZ20uXKKQeppaZJjeKfDOk+MfCuoeGddgafTr+Py5kRypIyCCCOhBAP4VX8GeDdC8A+E7Twx4ct3g0613FFkcuxLMW" +
                "YknqSTWta6np2oSTR2GoW128DbJVglVzG3o2DwfrXyD4l+K37RHj74jXvhX4deHLjQLbTbtomk+zjLBWIDSyyjaFPXA/WgD7" +
                "Jryj4f8Axfl8cfEzxZ4Nk8J3ulLoDsq3czZE219nIwNpP3gMnIr0+xF2NOthfsj3YiUTNGMKXwNxHtnNTbUDFgoBPU460Acp" +
                "48+HfhX4laDFoviywe7tYZhcR+XK0bI4BGQV9iRV3wp4L8L+CNIXSvC2i2ul2qgZEKfNIfV2PLH3JroMiuO8afE/wJ8Pki/4" +
                "S7xHbabLMhkigOXlkUHGQignGe9AHY0VyXg34h+GviD4Vn8ReELp9RtYXeIoY2jfzFAO3DDgkEY+tct8GPiV4p+I9jrk/ifw" +
                "XN4abT7kQwiQOBMCDkfOAcrgZI45FAHqx7V+VXjL/koXib/sK3f/AKOev1Vr8qvGX/JQvE3/AGFbv/0c9AGHRRRQAUUUUAFF" +
                "FFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABWlqf/HnpP/Xr/wCzGs2tLU/+PPSf+vX/ANmNAGbRRRQAVo6b/wAeerf9e3/s" +
                "wrOrR03/AI89W/69v/ZhQBnHqaKD1NFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFdv8AB/8A5Ln4I/7C8H/o" +
                "VcRXb/B//kufgj/sLwf+hUAfp1TJIo5Y2jlRZEbgqwyD+FPNcXJ8V/hrDrk2iTeOdEi1GGTypIJLxFKv3UknGaAM7xT8D/hX" +
                "4wLyaz4MsPtDDBuLVPs8n/fUeM/jmvFvEX7FPha6m87wt4s1DSjuB8q7jW5UD2I2kfjmvqtZEaISq6mMjcGB4I9c+lEcsUq7" +
                "opEkHqpzQB5p4usvHvg74FR6X8OSdb8SaXawW0Mt0oeSZVwruAxwXwCQCfzry34OfF/42a18Rrbwb8QfA8sdvIjmXUDYSWzQ" +
                "bVJBY/cIJAHGOtfUFGKAOJ1T4p+CtH+JWnfDy/1UxeINRQNDB5TFfmztBYDAJwcD/Guvu7e1vLSWzvIo57e4QxSRSAFZFIwV" +
                "IPUEdqwr3wL4R1HxjZ+Mb3QLSfX7JQkF+6fvIwM4x24ycE9M15X8fPgp4m+KV3pOq+GfFY0m60yJ0W1mZ1ikJOQ4ZOVbtnB7" +
                "UARn9lL4aW/xB03xXpTahpsdlcLdf2dFLmF3UhlwTllGQMgH8q98HSvjnwN4X/ay8J+OtI0q81OW+0H7Qguprm7juoBACN/L" +
                "/ODtzjGDmvsKU7YTjrQBDOSSO+KgLFh5SNtdurf3R605fu7T1PqahgdY9Rm851XKLsyeo5z+tI16GL4llEQt7OIYRRvPueg/" +
                "rXNx2xmvgzt8u1lXPtgmtjxG5bW3U5ICpioEgZ9EguYyHlWVsx7sEg//AKqxldsa2INHz9ra2Kbzk8Y6Hsa2blUjgfKdBkj6" +
                "VCNFmjhS7teJn+aRCf5Gm3mojTpUeWKaW62H5cgKF7k1SRV7GE8lxbyvNA2+NhhwOR9RU0btt+9nOCK0ILm3uL1Y7myNo8mC" +
                "pDArJn2qCbSrmC1NxGY5LWN2GM8gA1LiK50uhTmXTnhP/LM5X6GtIBWwpyPpWJoDASXMa9FjU/iSa20I3DPrWkXoSydWdZsH" +
                "7tTModGVuQwwaikO2PIpwLtASmN2OM9M1Zm11PL/AIWfBHw98Kdc8Qaroup312dYZR5VywIgQEsFBH3jlj8x7fjXquK+Nv8A" +
                "hGf2vfEvjz/T9dl0aytbsHz0uY4bbYG6qkfMgx2I5719kLkIoJyccnGM0COF+KvxK074U+Bn8U6jp9zqCeelvHBAQCztkjLH" +
                "hRwea+WLr9p341+PblrT4ceCVtUZiqvbWr3si/VyNg/75r7ZvtPsdTtHs9Rs4L22f70NxGJEb6qQQada2dpY2y21jbRWsCDC" +
                "xQoEVfoBxQB8w/B7wf8AtISfEax8WfEXxLdW2jqj/aNPubtXM4KkKoiTKphiDngjFeo/EL4DeAvid4ns/EPieK+N3bQi3xbX" +
                "HlpKgYsAwxngseQRXqlMkljiXdLIkY9WYCgDA8H+CfDPgLw+ug+FNLTTrAOZCisWLucZZmYkk8D8q6KqUOraXcXf2S21G1mu" +
                "MZMMcys+PXAOaqah4o8O6TrWn6Lqet2VnqWpEi0tZplWScj+6p5NAGxX5VeMv+SheJv+wrd/+jnr9Va/Krxl/wAlC8Tf9hW7" +
                "/wDRz0AYdFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFaWp/wDHnpP/AF6/+zGs2tLU/wDjz0n/AK9f/ZjQ" +
                "Bm0UUUAFaOm/8eerf9e3/swrOrR03/jz1b/r2/8AZhQBnHqaKD1ooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiig" +
                "Art/g/8A8lz8Ef8AYXg/9CriK7f4P/8AJc/BH/YXg/8AQqAP06NfN3iP9jz4e67r13q1vrWs6c13O08kMbxugLNlgu5cjqe5" +
                "xX0jRmgDgfGnw4j8TfBub4c6ZrFzpEX2WG1gu1Jd1WLbtDcjcCFweRnNeOfCf9mrxl8OviVYeI7r4gpc6bah/MtLcSKbnKlQ" +
                "rAnbjkHv0r6hyKM4oA8k+IHhn4t6r8VPCWqeC/FMOm+GLNlOpWjSFTJiTL5XB8wMnygE8EV63RVS/wBT03SrU3WqahbWNuDg" +
                "y3MqxqPxYgUAcB8Z/EXxE8M+CYb/AOGvh9db1VrtI5YjEZjHEQcsEBBPIA9s5qx4w8beIvCPwWfxk/hh7/XYbSGWfS4SSI5X" +
                "2hwSMnapJJx2FddpPiHQNeWZtD1uw1QQnbIbO4SbYfQ7ScVw3ij48fCnwdrFzo+ueLIY9RtW2TW8MUkrRt1wdqkA+2aAPD/B" +
                "37XfiLX/ABno/hvUvhyofUbqO2LWk8m9NzAFgjLzjOevQGvrl1BXae9eIad+0z8FtU8Tafpdhf3El7eTrbwzHT2VVdyFGWIB" +
                "AJOK9xI6UAVZFCsFAHTvSRIrk7lDAeozRMf3tMN1bWqbri4SIf7RxS6mvQ5nxJHH/bCsrYZo1LA+mcZrBZhHZ3Mk1s8y20hY" +
                "QAjMqZHIJ9M1Lqkp1G7Dwc+Y2DIOSVBrSh0p5xHH5mxiD5e/7rn+JT9Rj8qy6j6DfBerXV/aXsN9Ebcwz4hiZtzKh6DI610E" +
                "gsb2OaHzI2JRkY8ZANUY4IdNdJ7qAweWCqiOMY59x1qhbQQXkzExBjAxVS3BYHkEjt1qg9Dm9D0ldH1UXt4z3MYUgB5Cxb5u" +
                "Cq84AroZNQMuhfZ1Vgsju8hxjCliQo9zUl5BBbQE25jR5CYwo/vH6egzU2lWy3j2oZfljbdjOeBwDQM1dEsGs9OkuJl2z3BD" +
                "MPQdh+VaC/eX61YkGI8VEo+anaxKehNJxEc0RNiBmwW2gnA6mmF8jy24PpUiFY4nZjhV5JPYVXUn7J8b6p+2Z4li1S6sNO+G" +
                "sSSQStFtuLiRnBDYAZVQYPHSvo3SfGPifxB8DF8aab4XaDxHcadJcwaROTzMAQq84OGIBHQ4Irg4/wBqb4HLqE6tf3VvKJCG" +
                "mbTXw5H8WVBJ/Gu18J/HH4W+NtYh0bw/4rgn1GfPlW0sbxPIQMkLuAycA8UyD5pPxC/bF1hytn4XvLME4+TSEjA/GQf1r6B+" +
                "BR+MbeGtRk+L+0XTzqbJXEQlCYO7d5fGM4xnnrXrvFRQz29wrNbzRyhWKsUYNgjqDjvQB578Z/B/jHxv8PG0TwT4j/sLUTcJ" +
                "I8vmvEJowDmMunIySD+FfNKfsgfE7UmDa58RrPn72ZJ5z/49j+dfbvFFAHzH8Lv2T4/APj3TfF9/41l1C409zJHBb23kq7FS" +
                "vzMWJI56d69b8WfCDwf408e6F411uK6bVNEKG38qbZG+x967xjnDEngivQc0ZoAK/Krxl/yULxN/2Fbv/wBHPX6q1+VXjL/k" +
                "oXib/sK3f/o56AMOiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACtLU/+PPSf+vX/wBmNZtaWp/8eek/9ev/" +
                "ALMaAM2iiigArR03/jz1b/r2/wDZhWdWjpv/AB56t/17f+zCgDOPWig9aKACiiigAooooAKKKKACiiigAooooAKKKKACiiig" +
                "AooooAK7f4P/APJc/BH/AGF4P/Qq4iu3+D//ACXPwR/2F4P/AEKgD9OicV8c+K/iZ+1bJ4w1bTNB8FT2lpBcvHAYNK80GMMd" +
                "reY2Q2Rg596+xqKAPiAWn7amvkb59QsEb1ktbbH5YIrvPhP8Of2jNK+JVjrvjzxnJJo0Qb7VaS6i1z54KkBQg+UfMQc54xX1" +
                "HRQAV5H8afglZfGO20iO58QXWkSaY7lfKjEqSB9ucqSOfl4Oe5r1z8KKAPH/AIPfAbQfg9d6lfadrN9ql5qESwyPOqoiqpzw" +
                "q989yTUHiX9mb4VeK/F1/wCJ9W0+/N7qEvnTrDeMkbOerYHIz3wa9T8RXOq2XhjVLvQrNb7VYbWSS0tmOBLKFJVT9TgV8XTa" +
                "d+2D8SZpLe8fUNBs3yrBpE06ID0+X52H50Ae+aN8GvgJ4I8U6bJbabptvriyqbNL3UGkkMg5BVHflvTivZ/4a+S/AH7IN3pf" +
                "ivTvE/jbxj9vubK4juhbWasd7o24BpXOcZA6CvrU9OKAM7ULmC0/fXD7VxwByWPoBXAy3lzqGoTmNJCWbG0DJAHQHFdHrNhf" +
                "z6hJKYjLGRiPYeUH0NaWhWRtbVt8RjkdsnI5x2qJK5qnZGTpOilJlmuj8gywibjH4V0XkwzKUfBT06Y96kmUbtwAyPSmwqz5" +
                "3ruBBBz6UKIm2c7fTi8u5pUO+OFhCme395qq2+lGBGu9JdJC+T5bMecdMsOv0NX9W06JRIFhkG4DySjHAb35pbOyv5MNCY9P" +
                "kAG50IcP/wAB6UWGnZGGmlpZrBdTSolxOW/coPlTPPfnOCcmul8PQxw2H7s7suRvPJYdqxNasrtL+3FzIJjIrgOo2/MOenpi" +
                "uh0SRpdMgLKFZTtOBgHHcUDb0NOb/V1Xyi8uOBzViblMVA8e/wCWqJQ8yRyocdu4qaJQIsckH171XSNVk2EgMw6VaKK0Zjb7" +
                "pGDQiXboeHy/Az9nnxjqN5JY6Vptxd+a3njTdRYFXz8wKo+F5zxitHwj+zb8LvBXi+z8U6LYX32+yJaAT3bSJGxBG7Hc4J6m" +
                "vJ7r9jq50/xnBrfhD4hTadAlz52JYT58K7s4V1b5j9QK9l+N3in4i+EPAdvqXw40AazfC4CXOYGnaKLaTuEakE5IA9s0yT1C" +
                "4gW4tJrZnZVlRkLIcMARjIPrXmvwg+D+n/CLT9Xs7DXL7Vhqdws7NdALswCAAB1PPJ78VxfwH+PXiH4n+Ib/AMNeJfCP9l3t" +
                "jbee11AHWMkMFKsr8oTnI5PQ175dXdrY2sl3e3EVtbRLukmmcIiD1JPAFAHlfwu+E+t+APGfivXdT8a3WvQa3LvhtpQwEI3s" +
                "2WyxBbnbwBx+nl3xO+G/7Sl98S9V13wT4zl/sidw1pbw6mbfyEwPkKHC8HvzmvqDT9V03VrYXWlaha38B6S20yyKfxUkVcoA" +
                "8/0iP4k6R8Dlj1GS21fx7b6dIQSR5c1wAdgJGAT90E8AmvBPBPxK/akk+IWkaP4o8EyzadPdLHdyTaYYVjiLfO4lXCjaDkdc" +
                "4r67ooAK/Krxl/yULxN/2Fbv/wBHPX6q1+VXjL/koXib/sK3f/o56AMOiiigAooooAKKKKACiiigAooooAKKKKACiiigAooo" +
                "oAKKKKACtLU/+PPSf+vX/wBmNZtaWp/8eek/9ev/ALMaAM2iiigArR03/jz1b/r2/wDZhWdWjpv/AB56t/17f+zCgDOPWig9" +
                "aKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK7f4P/APJc/BH/AGF4P/Qq4iu3+D//ACXPwR/2F4P/AEKgD9Oq" +
                "4n4hfFPwV8MdPt7vxbqhtmuiRbwRRmSWbGM4UdhkcnA5rtq4fx78KvA/xLNgfGGkG+bT2YwOkzxMobG5SVIyDgcH0oA8C1r9" +
                "tfQ01CO28LeCb/U0Zwpe6nWFm9lRQxz9TX1Vpl4dQ0izv2t5LY3MCTeTKMPHuUHa3uM4Nc74a+GngHweo/4RvwjpmnSD/lsk" +
                "AaX/AL7bLfrXW4oA8m0P4Z+LdM/aB1n4g3fjea70G+gMcOkFnxGSFABBO0BcEggZ5+ufWaMivIPi98fvCnwkePTr23uNU1ye" +
                "Hz4bGD5QEJIDO54UEg9ATx0oA9eJAzmmQzQzx+ZBKkqHoyMGB/EV8Jy+JP2jP2i5nt9CtZdB8LzHaxgJtrbb/tSn55fcLx7V" +
                "9RfBP4WS/CXwHJoFzrj6vc3Fy11K+0rHGxUDagJJA+XOe5NAFD4jXfxog+JvhCPwBZW83hdpF/tZn8vON+HDbjuChORt5zXr" +
                "g6UYGc0tAFS9kigQSykqg4yBn86qpq9huP73n/dNaUiLIhR1DKwwRXI6lpktnMWQFoCeCOcexqWykbUmrWIcfv8A/wAdNC6t" +
                "Yh+Z+3901zIDDPBOfQGhS+/Oxv8AvmldjudHbXK38v2lHicZ2qhPKD3Hqavqw+dIY8N9OCa5i3WJ7ld9urODy2zBP40sJfGy" +
                "OK4bNzJu8vPKgnpz9KpDZo6l5f2nTvtwVh5j5GM/wmp11bTY2CrNtVeAAh4rEaOBxNcJA6tv2oXBLKAMHrVTYefvH/gJqQOo" +
                "k1vTmXAmP/fJpg1jT8j98f8Avk1zPlsUzhvfg1pabpD3brJLlYAcntu9hRcWx1EEsVxEssfzL2JGKkfdsbYAWxxnpmhVCKFU" +
                "YA4AHanVSIPGfg3cfHCbWvEsfxZtYIrESg6eyCIHJY5VNh5TGMbua7jwx8R/BfjHW9W0Xw3rkV/f6Q/l3cSIylDkrkEgBhkE" +
                "ZGea6zAHNcp4Z+HPgzwdrmq634b0KGwv9WffdyozEvyWIAJIUZJOBimB1SqgZmVAGb7xAwTXM+P/AAXp/wAQvAepeEdUubi1" +
                "tb9VDTW5w6FWDAjPB5A4PWvlvxZ8Uf2iPhN8UL+68Raa2ueGbq7cWsQtwbd4ix2LHIgyj7ccNnnsa+u7bVrdtCs9V1ArpiXM" +
                "UchS6cIYmcA7GJ7gnH1oA+NNS/ZJ+JnhO8a++HPjuOYg5AEsljN9MqSp/MVUHxI/ar+FpCeKNEu9YsImwZL20FyhA/6bRc/m" +
                "a+5gQRkHIo/lQB5r8GvibefE74dt4m1XQm0OeG4e3kQsTG4UA70LAHHOPYg8103hLx94P8dw3s3hLXbfVkspfJnMO4bG7dQM" +
                "g4OCODiuiaGKS3eB41MTqVZMcEHqMVyfgb4a+DfhzBqEPhDSBp6ahKJp/wB4zliM4ALE4AycAetAHYV+VXjL/koXib/sK3f/" +
                "AKOev1Vr8qvGX/JQvE3/AGFbv/0c9AGHRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABWlqf/HnpP/Xr/wCz" +
                "Gs2tLU/+PPSf+vX/ANmNAGbRRRQAVo6b/wAeerf9e3/swrOrR03/AI89W/69v/ZhQBnHrRQetFABRRRQAUUUUAFFFFABRRRQ" +
                "AUUUUAFFFFABRRRQAUUUUAFdv8H/APkufgj/ALC8H/oVcRXb/B//AJLn4I/7C8H/AKFQB+nVFFFABRXk3ifwL8RtV+Ovh3xd" +
                "o/jg2HhWwRFu9J8xx5uCd42D5X3AgZbkY4r1mgDyPxV4f+L938dPDmseHPEUNr4Ht0QahZM+N+C28FMfMWBABzxXY6/8OPAv" +
                "irXbPXfEfhew1TUbNQkM9xHuKqDkAjowBJPIPWurrO13WtP8N+HdQ1/VpTDYafA9zO4UsVRRk4A6nigC9HFFBCsUSLFEgwqK" +
                "AqqB2AHQVw3xZ8U+KvB/w4u9e8GeHv7f1WKSNVtgjSYQn5n2p8zY9B61iHV9C/aF+CWrw+E9avdKt9Q3WhuWi2ywSIysQVB5" +
                "BGM4PINdP8M/Bk3w/wDhzpfhO41mbWJbJWDXUowW3MWwAScKM4Az0FAGn4O1TV9b8E6Nq2vaUdJ1S7tUlubM5/cORyvPI+h5" +
                "FbtcP8Svil4W+Fnhwax4kuHLStstrODDTXDdwoJHA7k8D8q2fBnizTvHPgrS/FekxzR2WoxebGk6hXXkgggEjqD0NAG/ikKh" +
                "lKkZBrl/GvxC8IfDzTINR8X6zHpsFxJ5UO5WdpG74VQScDqcYFdFaXltf2MF9ZyrNbXEayxSKeHRhlSPYgg0AUrjTB8zQNjP" +
                "8JrLkjlgfEqMPrWvpuu6LrE13DpOrWl/JZSmG5W3mWQwuP4XAPyn2NMvde0Gy1i00W/1eyt9RvQTbWk0yrJPj+6pOW/ClYpM" +
                "zrdyjs+3cQCQB9KsWN3ZpH9rkmjizHnYSAR3YkfWpNY1Hw5oFl9v13ULLSrYsE866mWFCx6DJIGafc/2Hb6c+q3cttFZJH5z" +
                "XckihFTGd288Yx3oQ7oy45hJZxy7SDIS+D7mpY4JpWKwxsf5VoaVdaJrGnxano93bahZy8x3FvKJEbHBwQcUaV4h0DWpbqHR" +
                "NYsdRe0fy7hLSdJDC3owU8Hg9aVhXFttLAAe6be390dK0woUAAAAdMVmX3iHQtM1Sz0vUdYsrS/vjttraadUknP+wpOW/CtS" +
                "nYkKKKKYHkmt/DTxfqX7QOj/ABAtPHM1roFlCqS6OC+HIVgVAB2kNkEk8/pjorf4q+Crr4pz/DSDU3bxHAhd4PKbZwocqH6b" +
                "gpziu4zXn+o2nw00DxxP4ym06yTxRJH5Ul1ChadlxjBA4BwAM9cVnUqwprmm0l5lRhKbtFXO/A3DmvO/i38J9L+Lnhe00LU9" +
                "WvNNS1uhdLJbYO47SuGB4PB/CrP/AAtHQgcfY73HrtX/ABrd0rxl4f1dljtr0RzNwIphsbPpzwa5qePw1V8sJps2nhq0FeUW" +
                "cR8UvFniX4VfDHSpvBfhibxPPbyQ2BR1eUpEqEb2CfMSdoGfU128PiW0tPB+n+IPFEtv4eFxBFJOl7MsawSOoJjLNgZBJH4V" +
                "vA1wnxV+GGj/ABY8F/8ACN6xeXFkI51uYLi3wWjkAIBKnhhhjx+ortOc7e2ube8to7q0uI7iCUbklicMrj1BHBFTYr5++BPw" +
                "V8b/AAn8RasureMYdT8PTQ+XbWUJfBfcCJCjcIQMjgnOa+gaACvyq8Zf8lC8Tf8AYVu//Rz1+qtflV4y/wCSheJv+wrd/wDo" +
                "56AMOiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACtLU/wDjz0n/AK9f/ZjWbWlqf/HnpP8A16/+zGgDNooo" +
                "oAK0dN/489W/69v/AGYVnVo6b/x56t/17f8AswoAzj1ooPWigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACu3+" +
                "D/8AyXPwR/2F4P8A0KuIrt/g/wD8lz8Ef9heD/0KgD9OqKKKACjNFeYfGY/FweHdN/4VEtsdQ+1D7X5vl7vKxxjzPlxnr3x0" +
                "oA9Hvnuo9OuXsY1lu1iYwxucK74O0E9gTivL/hJqPxM8UeEtbh+Mnhu1sHacwwwGJVWaAr84ZdzArnjPcfnXp9h9s/s21Go7" +
                "PtnlJ5/l/d8zA3Y9s5r5c+PXi/xb45+Kun/APwDefYTcosmqXSsV4K7yhI5CKnzED7xIFAHtOmeLPg34GiHhfSvEfhjQ0jkJ" +
                "+xQ3cUYVz1yAev1rvre5t7u3S4tZ454ZBuSSNgysPUEcGvnPT/2QPhJa6VDp+p3WqXmpOhzc/axEztjlljAwAPTn3Nef6C/i" +
                "b9mH426V4R1DV5dV8A+I5QsDzHHkksF346K6kruxwynP0APYfiR+zvpPxO+KWneL9d8QXY0+1gSGXS1T5ZQpJwr5+QNnnAzX" +
                "rUkmh+EfCzyMLfSdG0q2LEKAkcESDsPQAVrV8fftWeNvEeueLdJ+Cnha1uDJqHlTXITg3bOx8uMf7AxuJ6Zx6UAcJBHrf7Vv" +
                "7QrTS+da+ENL+o8i1DcD/rpKR+H0WvvS0tLewsbexs4VhtreNYoo16IijAA+gArhfhB8MtN+Fnw9tPD9qElv5MT6hdAczzkc" +
                "/wDAR0A9B7mvQqAPL/ht8FtA+GXijxH4g0nU7+7m1yTc8dww2QrvL4GB8xyep5x+NfNXjuaf4j/t5aRolrLIbfR7qC33I33F" +
                "gHnSn2+bcK+19Y1K30bQtQ1e6YLBY28lxIT2VFLH9BXxj+yHptx4m+LXjD4h6gjPJGjASNz+9uJCzc+u1T+dAHVftu3ezwN4" +
                "Tsc/67UJZcf7keP/AGevQPEujOP2KJ9JjVneLwrEwHclYlf+lePftuXLza54I0tW4MdxJt92aNR/KvsDR7KO28Nafp0iK0cV" +
                "pHCysBggIBgigD55/YxuXn+CuqWsiN5cOryhSQcENFGSBXPfA34c+N/h5+094qtn0O7h8LzRTqt88ZEMqGQNDtboW7Y6jmvq" +
                "7TtK0vR7IWWk6da6faglhDaxLEgJ6naoAryD43fHMfB3VfDdq/h1tTt9VZ2nm84xiKNCobbwdzfNnBx096ANfxz8EPDfjz4l" +
                "+HfHeo397bX2imPEUDDZcCOTzEBzyuGJ5HUGvVKhtLqG9soLy2kEkE8ayxuP4lYZB/I14X4I/aCbxJ8edc+GGtaCmkNbTTQ2" +
                "MxlJeZoicq4PALKCwx6UAd58SPi34Q+FcWmSeK5rpBqUjRwi2h8wjbjczcjAGR7+1drYX9nqmm22o6fcJc2l1Gs0M0ZysiMM" +
                "gj6iuB+MXwr034s+A5dCuZFtdRgbzrC8K58iX3x1Ujgj8eoFZ3w18La18Ivgf/YmvazHqt1YebJE8e4pEGPyRru5IBOe3Won" +
                "NQi5PZDScmki3458ZzQzyaJpMxjdOJ51PIP91T29zXmBOWLE5JOST3pzNJLIWYtJLI2SepZjWvF4U8RzlRHo1183Qsm0fmel" +
                "fm2Jr18dUc0m10XY+vo06WFppN2ZqaJ4c0abw8uta9qEtrBNN5EQiGeemTWP4i0V9B1qTT2k81AA8cnTcp6Gt/xUkei+EtK8" +
                "MvMsl7HIbiZUOQmc4Gfx/StLU9OsPGosrrTtZgjvUtliNrLwzMOtdtTCwlB0YR/eRS66tvf7jlhWlGXtJv3W3/wBfA/ja4W6" +
                "i0XV5jLHJ8kE7HlT2UnuPQ16oOcV80TRTWtzJBIDHNExVvVWBr37wvqTat4Ysb2RsyPHtk/3hwf5V7GSY2dVOhVeq2/yOHMs" +
                "PGDVWGzNqiiivpjxwr8qvGX/ACULxN/2Fbv/ANHPX6q1+VXjL/koXib/ALCt3/6OemBh0UUUAFFFFABRRRQAUUUUAFFFFABR" +
                "RRQAUUUUAFFFFABRRRQAVpan/wAeek/9ev8A7Maza0tT/wCPPSf+vX/2Y0AZtFFFABWjpv8Ax56t/wBe3/swrOrR03/jz1b/" +
                "AK9v/ZhQBnHrRQetFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFdv8H/+S5+CP+wvB/6FXEV2/wAH/wDkufgj" +
                "/sLwf+hUAfp1RRRQAUUUUAFfHWrX9v8ADb9vltc8TOLXStetgsF3JxGu+JUBJ9A6bT6ZzX2LXA/FH4UeGPiv4bGk+II3iuIC" +
                "XtL6HHm2zHrjPVTxlTwfrg0AYvij4OW3ir40+G/id/wk15atoyIBZRDKS7WLDDZ+UHdhuDkV4p+0vqlp46+MngD4b+HZUvdX" +
                "tLzddeWdwgMjJ8re4VCx9BU8X7Onx20mzfQtC+M7RaJ9xENxcRlU9NozjjsDXpHw0+DHgr4Hafd+LvEWuxXusMmLnWb8iKOA" +
                "N1CbiduT1JJJ/SgD2mXU9Ot7+DT59Qtory4BMMDzKskuOu1Scn8KV7Cxlv49Qks4HvIlKRztGpkQHqA2MgV5nrPwm8JePPid" +
                "4b+LMet3U0unRxvbC0mVre4VWLI2cHjLHODzXq3agAAxRXhnjP8AaCtvDXx10T4YaZoJ1iW7lhhvZ45sNbPKflVVwdxCkMck" +
                "cGvc6AMDxr4ai8ZeBNa8LTXclmmqWr2xnjGWj3DGcd/p3rlPg38KLL4ReCJdAg1E6lc3Ny1zcXRi8vexAAAXJwAAO/rT/HPx" +
                "k8KfD/xr4e8Ka3Feve66yrC8EQZIgz7FLHIP3vQGvR+1AHxB+1a39qftFeBtEBB/cW6YHUGS5I/oK+3NuFwo6DAFfI3xP8B+" +
                "LPEf7a3hXUk0K8n0OI2cv21Ii0KJExd9zdFIbPBPcetfXhoA+aP2dfjH4w+IXxA8Y6B4suLeVbIfaLRIolTyQJCjJkcsOV68" +
                "+/NeifGv4PWfxh8L2Oly6o2lXdhcefBciLzRgjaykZHBGD16gV4t8C/Ani7wr+1d43uLzQbu10Ypdqt3JGVikDzq8W1jw2Rz" +
                "x6V9X6tZ3GoaJf2NrePZXFxbyRR3CfehZlIDj3BOfwoAreGtETw34T0nw/FcyXSabaRWizSfekCKF3H64rzbXvgR4b1j44aZ" +
                "8VxqlzY31k8c01rEqiO4eMYVi3UcYB9QO1aHwW+H3if4ceEbzSPFPiyTxHcz3bTxyMzssKFQNoLknkgk9ua7DxroD+KfAWue" +
                "HIrt7STUrOW2SZDgozKQD+dAG3FNFPCssEqSxtyHRgwP4iua8fBj4G1AL2CE/TeK+ev2QrvxfpP/AAlfw/8AEemXlvbaTMJY" +
                "mnRgIZSxV4wSOQdoYY9z3r6i1Kxj1HS7mwl+5PGyH2yOtc+JpupRlBbtM0pSUJxk+jPA/DgB8VaWGGR9pT+dbvivxHrlv4p1" +
                "Kzg1S4jgSTasaNgAYHFYunRnRvF9pHqH7o2l0olJ/hAPX6V1ut+EP7W1671KDX9MSK4feqtJyBgda+Cw8azw0qdL4lLXWz2P" +
                "pqs6ftozntY5+LwtPPbjV7zUoBpjDfJd79zZ7ptPJbPFGgSwXHjnSV0628iGOcBcnLsOcsx9fbtWkfAV15PlDxLpvl53bPOO" +
                "M+uKtWFpovgmSTU7vVYNS1NUK29vbnIBI6mtoYacZxlKPJFNNttX07Gcq0ZRlFPmb0Sscx4rZG8Y6qY/u/aG6evevUvhwrr4" +
                "LgLDAMshH0zXjQFzqGoYVTLc3MnCj+JmP+Jr6E0TTV0nRLPT1x+5jCsR3bufzrsySLqYqpXW2v4swzFqFGFJ7/5GlRRRX2Z4" +
                "AV+VXjL/AJKF4m/7Ct3/AOjnr9Va/Krxl/yULxN/2Fbv/wBHPQBh0UUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABR" +
                "RRQAVpan/wAeek/9ev8A7Maza0tT/wCPPSf+vX/2Y0AZtFFFABWjpv8Ax56t/wBe3/swrOrR03/jz1b/AK9v/ZhQBnHrRQet" +
                "FABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFdv8H/+S5+CP+wvB/6FXEV2/wAH/wDkufgj/sLwf+hUAfp1RRRQ" +
                "AUUUUAFFFFABXl2t3Pwx+OGma/8ADg64NQexkX7bFZu0ckDo/DKxGDhhg4yO1eo1x3hf4ZeC/BviTWvEXh3R1s9R1py93KJG" +
                "bdltxCgnCgsckDvQBr+FfDOmeDvCem+GNFSRNP06EQwiV97YznJPckkmrur3smm6Ff6jDavdy2tvJOkEf3pSqkhR7nGPxq7X" +
                "ivxO+P8ApXw7+I2g+CItFl1m/wBReP7T5MwU2qyPtTAwdzHrjjjHrQB4z+y74Q1fxh8U/EPxh8V28oljmkW385CC1zJ99hkd" +
                "EQ7f+Be1faFIFAHHH0rE8X69F4W8Ea14jmAKabZy3OD0JVSQPxOKALN/4f0HVdRstR1LR7K9vLFt9rPPArvA3qjEZX8K0z0r" +
                "56/Zc+Injr4j+HPEmqeMr5L2O3vUjtZFhWMqSpZ0+UDIGUxnnmvoU9KAPGbX48aZdftEzfCCPRJy0e6M6j5oI81Y/MK7Mfdx" +
                "xnPXtXs1eG6F8B30j9pLU/i1Nr63NvcmWWGy8kh45JE2nLZwVA3Y47j0r3KgAxRRRQB5NpPhD4pWv7QWq+KdR8YpP4InhK2+" +
                "leYxKkqAo2Y2rhgTuByfxr1nGRzXzf8AtRfEzx98NB4TvvCV7FaWNzPKLrdEshlZNpVDuBwpBbpg19AaHqkOueHtN1m3x5N9" +
                "bR3KY7B1DD+dAF53SNC8jBVUZJY4AFCPHLGJInV0YZDKcgj2Nc3498JxeOfAOr+E59Qm0+PUofKNzAMtHyD07jjBHcZqn8Nf" +
                "Blr8OfAWneC4NYm1Q2Ku3nTkB23OW4XJ2qCcAUAM8ZeDE15ft1iVi1FFxzwso9D6H3rx+90+8025a2v7V7eVT0dcZ+h719K4" +
                "qC4tLW7i8q6t450/uyKGH614OOyeniZe0g+WX4M9LDY+dFckldHzRtX0qe0srm+uFt7K2kuJW4Cxrk//AFq96Pg/wyX3nRbX" +
                "Oc/crVtbG0so/LtLaKBPSNAv8q8unw9U5v3k1byO2Waxt7kdTjfBngkaMw1LU9sl+wwiDlYR9e5rusUYxRX1OHw1PDwVOmrI" +
                "8SpVlVlzT3CiiiukzCvyq8Zf8lC8Tf8AYVu//Rz1+qtflV4y/wCSheJv+wrd/wDo56AMOiiigAooooAKKKKACiiigAooooAK" +
                "KKKACiiigAooooAKKKKACtLU/wDjz0n/AK9f/ZjWbWlqf/HnpP8A16/+zGgDNooooAK0dN/489W/69v/AGYVnVo6b/x56t/1" +
                "7f8AswoAzj1ooPWigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACu3+D/8AyXPwR/2F4P8A0KuIrt/g/wD8lz8E" +
                "f9heD/0KgD9OqKKKACiiigAooooAKKKKACvF/wDhQGkS/tDS/Fu/1ma8Yss8OnSRDbHMECK2/PIGMgY6/SvaKKAAVzXjXUvB" +
                "ln4amsvHWoWFnpGp5s3S+mEaT7hgpkkdvSulrhPiV8KfC3xW0mw03xSLsRWM/nxNazeWwJGCDwcgigDY8G+DfDHgbw7HovhL" +
                "To7DTS5m2o5fezYyxYkk8Ad+ldHUNpawWNjb2VsmyC3jWKNc52qowB+Qp0/mi3lMAUzbTsD/AHd2OM+2aAPKvhz4e+LelfEP" +
                "xdf+O/EsOp+H7uQnSrdH3eWN5IIGBsATAxzk16zXlfwbn+MU9lrp+LtvawzrdAWHkCMZTB3fc/hztwTz1r1SgDjPFfxQ8E+C" +
                "fEWj+H/EmsCy1DWG22qGNmBywUFiBhRk4ya7OvmD9pf4Q+NPiB418Ia14Q06O7FqptrljKsZhHmBlc5Iyv3umTX07GGEShyC" +
                "wABI7mgDmfHPgnwh468PjTfGmmQ32nWz/aQZZDH5TKDlt4II4Jzz0qz4R1bwrq3hy3/4QzUbG+0i0AtYjZSh44wgACZHoMVp" +
                "6pptrrGj3uk3yGS0vYHt5kBwWR1KsM9uCa5X4bfDHwz8K/D9xonhgXJt7m4NzK91L5js5AXqABgAAdKAO2ryhPg4I/2h5Pi5" +
                "/wAJRekvB5X9mlfkB8vy8bs/cx823HWvV6KACiiigAooooAKKKKACiiigAr8qvGX/JQvE3/YVu//AEc9fqrX5VeMv+SheJv+" +
                "wrd/+jnoAw6KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK0tT/489J/69f/AGY1m1pan/x56T/16/8AsxoA" +
                "zaKKKACtHTf+PPVv+vb/ANmFZ1aOm/8AHnq3/Xt/7MKAM49aKD1ooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiig" +
                "ArtfhHJHF8bvBcsrrHGurQFmY4AGepNcVSEAjB5B7UAfrH/bWj/9Baz/AO/6f40f21o3/QWsv+/6f41+TPkx/wDPNP8Avmjy" +
                "Y/7if980AfrN/bWjf9Bay/7/AKf40f21o3/QWsv+/wCn+Nfkz5Mf9xP++aPJj/uJ/wB80AfrN/bWjf8AQWsv+/6f40f21o3/" +
                "AEFrL/v+n+Nfkz5Mf9xP++aPJj/uJ/3zQB+s39taN/0FrL/v+n+NH9taN/0FrL/v+n+Nfkz5Mf8AcT/vmjyY/wC4n/fNAH6z" +
                "f21o3/QWsv8Av+n+NH9taN/0FrL/AL/p/jX5M+TH/cT/AL5o8mP+4n/fNAH6zf21o3/QWsv+/wCn+NH9taN/0FrL/v8Ap/jX" +
                "5M+TH/cT/vmjyY/7if8AfNAH6zf21o3/AEFrL/v+n+NH9taN/wBBay/7/p/jX5M+TH/cT/vmjyY/7if980AfrN/bOjD/AJi1" +
                "l/3/AE/xo/trRv8AoLWX/f8AT/GvyZ8mP+4n/fNHkx/3E/75oA/Wb+2dG/6C1l/3/T/Gj+2tG/6C1l/3/T/GvyZ8mP8AuJ/3" +
                "zR5Mf9xP++aAP1m/trRv+gtZf9/0/wAaP7a0b/oLWX/f9P8AGvyZ8mP+4n/fNHkx/wBxP++aAP1m/trRv+gtZf8Af9P8aP7a" +
                "0b/oLWX/AH/T/GvyZ8mP+4n/AHzR5Mf9xP8AvmgD9Zv7a0b/AKC1l/3/AE/xo/trRv8AoLWX/f8AT/GvyZ8mP+4n/fNHkx/3" +
                "E/75oA/Wb+2tG/6C1l/3/T/Gj+2tG/6C1l/3/T/GvyZ8mP8AuJ/3zR5Mf9xP++aAP1m/trRv+gtZf9/0/wAaP7a0b/oLWX/f" +
                "9P8AGvyZ8mP+4n/fNHkx/wBxP++aAP1m/trRv+gtZf8Af9P8aP7a0b/oLWX/AH/T/GvyZ8mP+4n/AHzR5Mf9xP8AvmgD9Zv7" +
                "a0f/AKC1l/3/AE/xr8uPGLK/xA8SMjBlbVbsgg5BHnPzWB5Mf/PNP++aeBgYoAWiiigAooooAKKKKACiiigAooooAKKKKACi" +
                "iigAooooAKKKKACtLU/+PPSf+vX/ANmNZtaWp/8AHnpP/Xr/AOzGgDNooooAK0dN/wCPPVv+vb/2YVnVo6b/AMeerf8AXt/7" +
                "MKAM49aKD1ooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKK" +
                "KACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKK" +
                "KACtLU/+PPSf+vX/ANmNZtaWp/8AHnpP/Xr/AOzGgDNooooAK0dN/wCPPVv+vb/2YVnVo6b/AMeerf8AXt/7MKAM49aKD1oo" +
                "AKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAoooo" +
                "AKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACtLU/+PPSf" +
                "+vX/ANmNZtaWp/8AHnpP/Xr/AOzGgDNooooAK0dN/wCPPVv+vb/2YVnVo6b/AMeerf8AXt/7MKAM49aKD1ooAKKKKACiiigA" +
                "ooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKK19I8PX2s2OqahDJb29lpcHnXNxcybEBPCRj1djwqj" +
                "rRr3h2/8N3Fpa6m8C3dxbrcvbRybpLYNyqSj+FyMHHoRQBkUU0ugOCwH41a0y0OqaxZaXBPFHLeTpbo0jYVWZgoJx2yaAK9F" +
                "dbefD3xBa6v4r0tfss9x4WTzb8Ry8lMgF4wRlgM5PTArkkBlZFj+YuQq4PUnigAorX8SeGda8I602j+ILI2d4say7dwZWRhk" +
                "MGHBHbjuDU2r+FtS0TwzoXiC/kt0ttdR5bWIP+92KcFmXHAPbmgDCorb8LeFtU8Ya0+kaP5P2lLeW5PnPsXZGu5ufXHSsMHc" +
                "obsRmgBaK3da8KaroGhaDrOoeT9l163a5tPLk3NsUgHcMfKeavaP8N/HniHSYtW0Twte39jNkRzxAFWwcHHPYjFAHKUV0fiD" +
                "wL4w8K2cV54j8P3WmW80nlRyTgAM2M4GD6CucAJOFBJ9AM0AFFSR28808cEUMjSyuERdpyzE4AHvXZn4Q/FADJ8Catgd/KH+" +
                "NAHEUV1mp/DT4gaLpk+qat4Q1Kysbdd8s8seFRemTz71F4N8Da145uNSg0V7ZG062N3N9okKAoDjjg5NAHMUV1HirwJrfg/T" +
                "tEv9We1aHWrf7TbCCQsQuFPzDAwfmFcvQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRR" +
                "QAVpan/x56T/ANev/sxrNrS1P/jz0n/r1/8AZjQBm0UUUAFaOm/8eerf9e3/ALMKzq0dN/489W/69v8A2YUAZx60UHrRQAUU" +
                "UUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQB2vhbxjpWieB/E3hrUtAGpvq4R4J2cAWsiK" +
                "wV8EckbsjFM+I/i7S/G3ilNb0vQRoxaBUuE3hjPICcyEgDkjA/CuNoCu7KkaF3YhVUcliegFAH0lpWoRaH8KvAKWcPg2xm1C" +
                "zuJbi88RwDa5STCgMATuO7v6Uy48aXmnWsmopq/wiuWtV84Q2cZMz7ecRjb97jj3q/K2j+HfGPgLwZr8+mxNovhq4M51Hb5M" +
                "d1KuUDbuM5FY99aza58PdZ0XV9V8A3OtX89pb6YdHMMRVzKN+5goIGP0zQB22r+JvFM3xnl0Dw9pXh+Cwu9LTUbjVb2y3tHb" +
                "NHlzK4I3DIxjv+FfM+s3Gp+O/HUradpVrLfXjCGG10q38qOXYuAUTtkLmvorxHZap4j+FT+CPh5rdhr3iHTI4NL1uSAhJZ4F" +
                "BwqMTygY889iOuRXhvxITT/CHxOntPBkp019Jghha4s5WB+0iP8AesrZyDkkHFAHc6Bqvie4+I/wx8IeOPDf2a+0q52x3N2h" +
                "+0T27ZCowPBUYI564r0zTbvxVfa7r1nrfibwfq+lQWN8ILCwEbXUICts42/Lt74PWucmsNX1v9of4a31vDNeNa6LZXl5NnJj" +
                "T5g0jEnnlh+dbWhaVqtv4s8SXVz8H7LwzAbLUMa5FIxklyrYyCx+/wBelAHHfBvw/wDDqz8TRXmi+P5tU1OTSZxLZtYtGI1a" +
                "L94dx67efrivIYrDwJZeP1sbrXb7U/CScHULSDy5pB5eQQh6fPx9BXt3gPTrC/1vwr470axhs7XUfDl5p97DboEjiu4IcHgd" +
                "Ny8/hXg3gvw5a+KNaGnXviGx0C3SBp5Ly9OECrjIHTLc8D2oA9z+INn8Nbr4deB7UT69LK+lyx+Ho4owWmJYBRMMcEvtHFc3" +
                "8RrjVPAfwt8CeBV1Waw1+2Wa9v4rS4KGISHKo5U+pPHsa6D4nx/D7Rfhb4NtbiW+129h0ySLRL60PlRMA6lpXGfXGBzS+OJ/" +
                "hdo9jpHixvhvJ4j0zX4hKmpnUpN3mj70cmSSGGPXsfSgDM0vSr/4nfs+6Vo1r4ksX1vTtXmmnXVb3Y/lsCF5bJ6EY7da5v4M" +
                "Wcml/HiOwnaCaazhvY2ZCJI2ZIm5B7jI610l7YfDHUfgnrXjRfh2vh2R2+x6TJJePI9xMeNyjONq8889D6VyHwI4+L9n/wBe" +
                "V5/6IagDr9d8Uar4x+E/g3W9a+zG9Hi3yN1vAsQ2KOBgV6JA/hY/tN3yJ4p8QPrWZd2mNFiyX9zzhs9hyOOteW6Fp9rqfwO8" +
                "MW13r9toKDxTcyJe3KF0R1iygIHUk4FenPB8QE8Q32mN8ZNAXV7KA3dzD/YqebFFjJc8Zxgjp2oA4myk8LyfCb4m/wDCO+Jt" +
                "e1qQWkfnrq0WwRfvTjZyc5OfyFeO+EvEsXhW5v8AU47OW41N7V4LJ/O2xQO4KtI6/wAeFJ2jOAea9t1C9uvEehajpF38d/Dc" +
                "+nXEP+lrb6NsxHkcsyKCozjn3r5xlVUmkRJBIqsVVx0YA4B/HrQB0PiTxHDrGmaHo+n2c1ppuj25jjWebzZJZXO6WRm9zwAO" +
                "AABXOUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFaWp/8AHnpP/Xr/AOzGs2tL" +
                "U/8Ajz0n/r1/9mNAGbRRRQAVo6b/AMeerf8AXt/7MKzq0dN/489W/wCvb/2YUAZx60UHrRQAUUUUAFFFFABRRRQAUUUUAFFF" +
                "FABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAVa02xvNU1az03Tk33l1MsMC7tuXJwOe3PeqtAJBBBII5BHagD6NfTfCt" +
                "x+0X4R8G3ltb67c2FqbfWr67Pmm/ufKJCvuODswAPy7VgaFonjLQ7HxJ4S174Xapq/h6/llkhSC22yW9wCRFJHIBwOg+nTuD" +
                "4qlxcR3Qu47iVLgP5gmDkOG67t3XPvW+PH3joAAeMdZwP+n1/wDGgD1HSfDfhz4b6h4IfxhZ3lteeI7KeHUVedo304swWOVd" +
                "uCp55yT69RXk/jXw5P4V8X6r4fmvYr1rWUhbmNw4lU8qxI6HB5HY5rNv9S1HVbtrvVNQuL64YYMtxKZGx6ZPaquT/WgD6i8T" +
                "eHtP8WJpum+HL1m1210myh1HW4bsx2mjWyLvbcykb3fJ+X0HarfhzUNTuNWGl6FYat/wgelaFfGPVrwPjU52j/15duoJztH1" +
                "NfLEV5dwW1xbQXUsUFyAJ4kcqsoByAwHXB9a1V8X+K00MaEniTUV0oJ5Qs1uGEQT+7t9PagD1X4HeGvF9lrI1u+0y/t/Dh0a" +
                "7mjnlJFuWeHAZecZOOvtXi+k6VqOt3UGm6TYTX97KuUghXc7YGTgew5rS/4S3xV/Yg0MeI9SGlrH5QsxcMIgn93aDjHtWfp2" +
                "pahpGow6jpV7NY3kBJjngba6EjBwR04JFAHsvxL8MeIZ/Cfwr8M2+jXc2sw6RMsllHGWkjO9PvAdPqeK6Dwtokfw08C6jp3x" +
                "i1Kx/sLUwHi8N/8AHxc+Zn/WLtPyH1x+Y7+IQ+N/GEHiAeIY/EuoHVhEYBeNMWkCEYKgnt/+usW7u7q/u5Ly+upbq5kOXmmc" +
                "u7H3J5oA9++JfhbxF8RrWx134f3tjr/hOwhWK00rTsRy2PAyGiPVv14xiuA+EWpWHhb4uW914kmXTYLaC6in+05Ta5iZdjcZ" +
                "BJ4ridH1vWPD+opqOh6nc6bdr0lt5ChPsfUexqpPPNdXUt1cStLPM7SSSMclmJySfqTQB6R4l8f6V4p0PRvCXhzwTF4ftrfV" +
                "ReJHb3DTeY7fIeCM5ORXq3jPw54s0H41ah8UIJdP03SLCCNt9/cKn29VhAkgRerFuV+uK+YIJ5ra5iubeRopoXEkbqcFWByC" +
                "PcGrmr63rGv37X2uapdalcsc+ZcSFyPpngfhQB9Aa7aXXiL4ePF8C7KwXQ71TJrFhbEDUy5JJSQOcmMdgv8AKvnFlZHZHBVl" +
                "JBB7EdqmtL280+5FzYXc1pOAV8yCQo2O4yO1QUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRR" +
                "QAUUUUAFFFFABWlqf/HnpP8A16/+zGs2tLU/+PPSf+vX/wBmNAGbRRRQAVo6b/x56t/17f8AswrOrR03/jz1b/r2/wDZhQBn" +
                "HrRQetFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUU" +
                "UUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAVp" +
                "an/x56T/ANev/sxrNrS1P/jz0n/r1/8AZjQBm0UUUAFaOm/8eerf9e3/ALMKzq0dN/489W/69v8A2YUAZx60UHrRQAUUUUAF" +
                "FFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAF" +
                "FFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFaWp/8eek/wDXr/7M" +
                "aza0tT/489J/69f/AGY0AZtFFFABWjpv/Hnq3/Xt/wCzCs6tHTf+PPVv+vb/ANmFAGcetFB60UAFFFFABRRRQAUUUUAFFFFA" +
                "BRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFA" +
                "BRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABWlqf/HnpP8A16/+zGs2tLU/+PPSf+vX" +
                "/wBmNAGbRRRQAVo6b/x56t/17f8Aswr6Gvv2d/C8hJ0/W9StOOA+yUfyBrFb9n3ULSC9Sw8S20/2iLy1E0LJjkHnBPpXhwz3" +
                "Az+3b1TO2WCrLoeBHrRXqV58B/HtvnyI7C8A6eXcbSf++gK568+FvxBsc+d4WvHA7w7ZB+hrup4/C1PhqL7zB0Kkd4s46itG" +
                "70HXbDP27Rb+2x/z1tnUfnis0kK21vlPoeDXZGcZfCzJxa3QtFJkHoRS1QgooooAKKKKACiiigAooooAKKKKACiiigAooooA" +
                "KKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooA" +
                "KKKKACiiigAooooAKKKKACiiigAopCQOpxQDuOF+Y+g5o8wForRtNB12/IFlot/c56eXbOw/PFdBafC74gXuPK8LXiA95gIx" +
                "+prCeIpU/jml80WoSeyOOrS1P/jz0n/r1/8AZjXoFp8B/Htzgzx2FmD/AM9LjJH4KDXVr+z7qN3DZJfeJbaD7PD5beTAz5OS" +
                "eMketcNTN8FT3qL5am0cLWltE8Cor6Wsf2d/DEeDf63qV2R1CbIgf0JorkfEGCTsm38jZYCs+h7O3X8BSUrdfwFJX5qfQWCl" +
                "pKKBjizEYJJHpmqF1pGkXoIvdKsrnP8Az1t0b+Yq7RVxnKPwuwnFPdHKXfw38B3ufP8ACmn5P8Ucew/+OkVhXXwQ+HdyDs0y" +
                "5tSe8Nywx+ea9IorqhjsTD4aj+9mTo03vFHjdz+zz4Vkz9k1nU7Y+5R8fpWLd/s5d7HxYPYT2n9Q1e/UV1wznHR/5effZ/oZ" +
                "vCUX9k+Zrr9nnxZECbTWNLuh2yXjJ/MGsS5+CHxEt87NLtrkf9MbpDn88V9aUV1w4ixkd7P5GMsBRe10fGVz8MfH9rnzfCl8" +
                "cf8APNQ4/Qmsa48L+JbQn7V4f1GHHUtbP/hX3PS7mxgsa648TVvtU0ZvLodJM+BZIJ4Tia3ljP8AtoR/Oody+or77kggmGJY" +
                "IpB/toD/ADrNuPDXhy6BFzoGnTZ677ZD/SuqHE8ftU/xMnlr6SPhfIpa+0rj4b+AbrJm8Jadk/3I9n/oJFZM/wAGPhxOSf7A" +
                "aH/rjcyL/WumPEuGe8ZL7jN5dUWzR8h0V9TT/ALwHLnyX1O3/wB25DD/AMeU1mTfs7eHG5t/EGpRH0ZEb+grpjxBgnu2vkZv" +
                "AVkfNlFfQM37OMX/AC7+LX+klqP6NWdN+zprC/8AHv4mspP9+F1/xreOd4F/8vPwZm8HWX2Tw+ivYJf2e/GCH91qWlzD/fZf" +
                "5rWdN8CfH8ROyDT5h/sXQ/qK3jmmDltURDw1ZfZPMKK9Bl+DHxGi5/sJZB/0zuYz/WqMvwr+IcQy3hO9YeqbW/ka2WNwz2qL" +
                "70Q6NRbxZxlFdNJ8PvHMX3/CWqfhblv5VRl8KeKIP9b4b1NPraP/AIVsq9J7SX3k8kuxj0VcfSdWi/1uk30f+9bOP6VA9tcx" +
                "/wCstpk/3o2H9KtTi9mTyvsRUUFWHVWH1FIWA6mquhC0U3en94fnS7k/vD86YC0UhK+tG4eo/OgBaKTcPUUbl9RQAtFJketG" +
                "R6igBaKbuX1FG5R1YfnQA6im71/vD86XIPTn6UALRQAx6KT+FSpbXMn+rtpn/wB2Nj/Sk5JDsyKirkekavL/AKrSL5/922c/" +
                "0q5H4U8UTf6rw5qj/S0f/CodWC3kvvHyy7GPRXTRfD/xzN9zwlqn/Arcr/Orsfwq+IcuNvhS8Ge7lF/mayeKoLea+9FqlUe0" +
                "WcZRXoMXwY+I0v8AzAlj/wB+4jH9avRfAn4gyfet7CL/AH7of0FYyzHCR3qx+8fsKv8AKzzCivXof2fPGLjMuo6XD7F2b+Qr" +
                "Rh/Z01liPtHiaxQf7ELt/hWEs4wUd6iNFhaz+yeIUV9Aw/s5R5H2nxY+O4jtR/Vq0of2dvDi4M/iDUpf91EX+hrGWe4GP27/" +
                "ACZawVd9D5sor6mt/gF4Ei5mfU7g/wC1chR+iitaD4MfDeA5/sBpv+u1zI39a5pcR4RbJv5f8E0WX1X2PkLIpNy5xuGfrX2n" +
                "b/DbwDa48nwlp3H9+Pf/AOhE1rQeGvDlrj7N4f06LH921Qf0rmlxNRXw02aLLp9ZI+HIoJ5iBDBLIT/cQt/KtK38M+JLsgW2" +
                "gajLn+7bOf6V9xRwQRAeVbxR4/uIBj8ql3N6muaXE8vs0/xNFlq6yPjC2+GPj+7wY/Cl8oPeRQg/U1tW3wQ+Ilxgvpdtbg95" +
                "rpBj8BmvrWkrlnxJiX8MUvv/AMzRZdTW7Z8zWv7PPi2XBu9X0u1HfBeQ/oBW1a/s5EYN94s+ogtf6s1e/wBFcks+xsvtW+SN" +
                "VgaK6fieN2v7PPhWIg3Wsancn0BSMfoK3bX4I/Dy2A36XcXR9Zrljn8sV6PRXHPNMZPeozZYaktonK2vw48B2ZBg8J6dkd5I" +
                "/MP/AI8TW9baRpNkALPSrK2x08q3RcfkKuUVyTr1Z/FJv5s1VOC2QoZgMBiB9aKSisCrBRRRQOwo6/gaKF6/gaKABuv4CkpW" +
                "6/gKSgAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKWkooAKKKKB3FyfWl3sOjH86bRQFxxdz1Yn6" +
                "mmFVb7yKfqBS0VSbWxNiJ7W0kP7y0gf/AHolP9KhbStLf72l2bfW3Q/0q3RT9pLuFkZ7aFoT/f0TTj9bWP8AwqBvC/hl/v8A" +
                "h3TG+tqn+Fa9FUqtRbSYuVdjDPg7wkc58MaWf+3VP8KjbwR4MY8+FNKP/bqn+FdBRVe3q/zv7xckexzp8C+Cjx/wimlf+Aq/" +
                "4Uf8IJ4K/wChU0r/AMBlroqKf1mt/O/vD2cexzo8CeCh/wAyppX/AIDLTh4H8GL08KaUP+3VP8K6Cij6zW/nf3sPZx7GEPBv" +
                "hFTlfC+lj/t1T/CpV8LeGE+54c0xfpap/hWxRU+3q/zP7x8kexnpoWhJ9zQ9PX6Wsf8AhUq6Xpafd0uzX6W6D+lW6Kn2k+7H" +
                "yrsRLa2sf3LSBf8AdiUf0qUBR0VR9AKKKTnJ7sLIcHcdHI+hoLMerE/jTaKgYuTRmkooAKKKKYBRRRSAKKKKACiiigAooooA" +
                "KKKKACiiigAooooAKKKKACiiigAooooAKKKKAFXr+BooXr+BooAG6/gKSlbr+ApKACiiigAooooAKKKKACiiigAooooAKKKK" +
                "ACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooo74pgFFFFIAooooAKKKKACiirDWdwunpfsoFu8hiU55LDrxVxg5X5Vs" +
                "JtLcr0VYWznfT5L8KPIRxGTnncfaoOlEoSjZyW4lJPYSiri6ZetE0ohwqwi45I5jJxmkttOvLwIbeHcryCFWJwNxGcflV+wq" +
                "3S5Xdk+0hvcqUU50aORkcYZSVP1FTR2dzLZzXkUReGEgSMP4c9OPSpUJN2S1K5la9yvRVmSyuYra2uHT5LnPlYOS2Dg8fWm3" +
                "VpcWU/kXUZil2htpPIBodOaV2u347ApJ7MgoqQQzEAiGQg8ghSc09LO8kBMdpM4BwSsZP9KSpzeyYc0V1IKKmltriAAz28sQ" +
                "PQuhGfzqGk4uLs0NNPYKKKKkYUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFACr1/A0UL1/A0U" +
                "ADdfwFJSt1/AUlABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFB6UwNWy0DUr63FzGkcU" +
                "LHCPM4QP9M9arvpWox6mNNe1cXRPCf3vcH0rU8WEjUraEf8AHsltH5I/hxjnFVNLtbrVL8ebeSQpbxGRpnJJjjHpXq1MPRVX" +
                "6vCLck7N337+nl5bnJGpPk9pJpJhdeHtStLaS4ZYpY4v9Z5Mgcp9QKmKIPAfm7F3/btu7HONvTNa2groYurtdNlvZZTbSbzK" +
                "oCMuOuKZokumQeEEn1RGkgjvsqgGcttHX2HJ/Cu6ng6V/caSlGS3ula2tzCVaVveV7NdLHN3GnXdpaQXNwgiSfmNWb5mHrjr" +
                "ir0PhnVpokfy4omkGUiklCuw+lWtTgmTxlA17KJ4Z5UeOT+FoyRgD2HSreuN4f8A7eumvn1IXSP95NuF9NvtWMcFRTm57RfL" +
                "q7fO9vuLdedlbqr7HMfZLr7b9iFu/wBp3bPLxzn0rSn8NatbwPK0UchjGZEikDMn1ArctL6HUfE2oXtpHIky2JEQkXDFwMZx" +
                "69K4+Ga5jlaSGWRZWB3MhO4jvmuepQw9GPM7yTbSafRfm/uNIVKlR2WlrfiWtP0i/wBSR5LeNRDH96WRgiD2yaTUNLvdMkVb" +
                "uIBXGUdTuVvoRWlqZZPCOixxHFu+9nx0Z89/1rDaSZoI4mkcwoTsUk7R64rLEUqNFezs+ayd76aq+1tvmVCc5vmvpqPs0SW/" +
                "to5F3I8qKw9QSM12AutNudcXw5Jo0ItoJJBGd5+U4JJx74rkdP8A+Qpaf9d0/wDQhXZW+pwSeN5rL+y7RHEkg+0AHzOFPNd2" +
                "WNKFm0ryS1V7rtszDFb7bJ/8OZlzqOmabplppf2CLUowDM8hcqvmHsMdcA4qlrUVgk2mTRWws4LiBZZEQlsZb368VDDqED6P" +
                "c6ZfIzhWMls6gZjfPI+hqfXTsXRzgEpYxnDDIPJ6iirVVWlKWjSUbKyutbW7/iKFPlml6/PQ6u2u1mKrFqMyLfQN9mjECjyV" +
                "TuPy4zVWyvPs6tNcXtzEltcDzUa2VWnL4259Dj07VnC/hi0y11u9W4e7uTJEDBL5QjVf4QOwpdVm0+2gsra9srp1dBOiC65G" +
                "e7ccmvY+s+6pXV0k+vW1r9rvX8DjVLpbf06bkPiNHeG5Y389z9luPLZXhVFUkE8Ecmr1jc2q20jabHYxwxW0a3MskbkszcEE" +
                "DrzWT4nmuU1WSwN3NLbx7WVZWzyVzmpNLa303Qjqck96jTTmErbSBc4GRnI+tefGuljJ2Vklq30tf19H38jpdP8Acxv8vwNN" +
                "NRtwbZUm0ofZz+5AtpvkJPas7xBJbeTPBPFbJqaXI3NCrDehXOST7mrt1qiWdra3E9xrCpcqXQfaEzgevy1i+IIYkubW5ikn" +
                "k+2QCdjOwZsnjqKvGVrUJRVntfyTVlp91vImjC9RNqxr6Pc64vhW5ltnuZmLLBbJGufLA5Zhx+FVEvNTi0PULye9uoroXKRj" +
                "94VwSOcjpnirSai8uh6ItqptUjvRCVRiN+FBJP1JNWGTV47q/FpPp32eW5d9lyyk5zjoenSrs5Qioyk0o2013V7/ACEnZttL" +
                "V/kzI1e5nn8N6M1zO80shlkJdskjOBWBXY6hLey+HNRj1I2cjweUImtwpCgtyMjp0rjq8fMo2qxd27pb76afmjswr91rswoo" +
                "oryjrCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAVev4Gihev4GigAbr+ApKVuv4CkoAKKKK" +
                "ACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooA2bbX5orOO0u7K2v4of8AVeeuSnsD6U1fEOoj" +
                "VP7QJjZynleVs/d7P7uPSsiiuz65XslzvTYx9jT103N0eJrmHetjY2dnHIpV0jj+9kdSazft839j/wBl7U8jzfOzj5t2MflV" +
                "Sipni609JS7r79xxpQjsi9LqlxPpUGnyqjJbnMchB3qPTOelX18SzMsbXmn2d5cRDCTyplvbPrWFRTjjK8HdS7L7tvuE6MGr" +
                "NF7+1b46r/annkXWc7gOPpj09qvSeJJjHJ9m0+ztJ5htknij+Zs9fpWHRRHF14JqMtxujB6tGnp+sz2Nq9m9vDeWjncYZ1yA" +
                "fUelM1HVZ9S8uNoore3h/wBXDCu1V9/rWfRUvFVnT9k5e7/Wne3lsCpQUue2pLbSiC8gnI3CORXIHfBzW/N4tuTfSXFvYWaZ" +
                "YlWaL5wD6kHrXN0U6OKrUYuNOVr6hOlCbvJF+wutOgEn2/TftpY5UiUpt9elLqmpf2lfLP8AZxBFGixJEpztUds1n0VP1ifs" +
                "/ZaW9Fr69x+zjzc3U3Rq+l2iqLDSC7Jyr3kpkCn1C9Kj/wCEk1KR2+2iC+iY5MU8YIH0I5FY1FbPHV9oysuysl9xH1en1VzQ" +
                "1K6sLvZNa2ktvOf9YHlMinjjGeatx6rpdtbRpBpTzyLhj9pmLRh8ckIOKxKKlYuopuorXfkh+xjZR7eZsnxLqzSs00kU8TdY" +
                "JYw0Y9gO1Q399YX1sjJYPbXS4GVlLR7fQA9KzKKUsZWnFwnK6ffX/hgVGCaaVrGvZa/d6dpgs7RUVhKZTI6huoAxg9OnWqKz" +
                "xzakbrUIjcLI5eVUOwtn0Pbmq1FTLE1ZKMZO6Wy6FKlFXaWrNi61Sy/sp9O0zT2tY5XEkjPLvLY6Cseiioq1pVWnLp20HCCg" +
                "rIKKKKxLCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAVev4Gihev4GigAbr+ApKVuv4CkoAK" +
                "KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK" +
                "KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAV" +
                "ev4Gihev4GigBSDnt0Hekwfb86KKQwwfb86MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB" +
                "9vzooouAYPt+dGD7fnRRRcAwfb86MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB9vzooou" +
                "AYPt+dGD7fnRRRcAwfb86MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB9vzooouAYPt+dG" +
                "D7fnRRRcAwfb86MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB9vzooouAYPt+dGD7fnRRR" +
                "cAwfb86MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB9vzooouAYPt+dGD7fnRRRcAwfb86" +
                "MH2/Oiii4Bg+350YPt+dFFFwDB9vzowfb86KKLgGD7fnRg+350UUXAMH2/OjB9vzooouAYPt+dGD7fnRRRcBQDnt0Peiiimh" +
                "H//Z"
        @Volatile
        private var bitmap: Bitmap? = null

        @Volatile
        private var failed = false

        /** 解码后的位图；解码失败返回 null（调用方负责兜底文案），绝不抛异常。 */
        @JvmStatic
        fun bitmap(): Bitmap? {
            val cached = bitmap
            if (cached != null) return cached
            if (failed) return null
            synchronized(DonateAsset::class.java) {
                if (bitmap != null) return bitmap
                if (failed) return null
                try {
                    val raw = Base64.decode(BASE64, Base64.DEFAULT)
                    bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                } catch (ignored: Throwable) {
                    bitmap = null
                }
                if (bitmap == null) failed = true
                return bitmap
            }
        }

        /** 解码一次、进程内共享；每次给一个新的 BitmapDrawable（同一 Bitmap 实例）。 */
        @JvmStatic
        fun drawable(c: Context?): Drawable? {
            val b = bitmap()
            if (b == null || c == null) return null
            try {
                return BitmapDrawable(c.resources, b)
            } catch (ignored: Throwable) {
                return null
            }
        }
    }
}