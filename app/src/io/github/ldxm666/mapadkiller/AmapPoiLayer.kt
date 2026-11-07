package io.github.ldxm666.mapadkiller

import java.lang.reflect.Field

import io.github.libxposed.api.XposedInterface

/**
 * Removes the POI icons and names drawn on the map, while leaving geography intact.
 *
 * <h3>Why this is two mechanisms</h3>
 * The map's own POIs (shops, hotels, scenic spots) are rendered by the native engine from
 * tile data. They never pass through a Java-side list, so filtering item arrays alone can
 * never reach them. The engine does expose a switch for it -- `nativeSetProcessLabel`
 * -- and that is what actually turns the labels off.
 *
 * <p>Separately, overlay point/label batches <i>are</i> pushed through Java
 * (`nativeSetPointInfo`, `nativeSetThirdLabelInfo`, ...). Those carry entries
 * whose properties sometimes expose a `mainKey`; the ad-like keys used by the ranking
 * cards are dropped there so they cannot reappear as pins.
 *
 * <h3>Why the reflection is defensive</h3>
 * The obvious earlier implementation read `StylePointProperties.styleInfo` and
 * `.mainKey` directly. On 17.00.0.2005 neither field exists on that class --
 * `StylePointProperties` has `visible`, `mainPriority` and friends, while
 * `mainKey` only exists on `ThirdLabelItemProperties`. The lookup therefore threw
 * `NoSuchFieldException` on every call, the whole filter aborted, and the switch looked
 * dead even though the hook was installed and firing.
 *
 * <p>Every field read here is optional: a missing field yields null and the entry is simply
 * left alone. That keeps the hook correct across app versions whose scene structures differ,
 * which is exactly the situation that caused the bug.
 */
object AmapPoiLayer {

    /** Keys the ranking / recommendation cards use; these are the ones worth hiding. */
    private const val KEY_RANKING = 12024
    private const val KEY_PROMO = 12029

    /** Reads a field, tolerating both missing fields and inaccessible ones. */
    private fun read(target: Any?, name: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (missing: NoSuchFieldException) {
                type = type.superclass
            } catch (ignored: Throwable) {
                return null
            }
        }
        return null
    }

    private fun asInt(value: Any?): Int? {
        return if (value is Int) value else null
    }

    private fun writeBoolean(target: Any?, name: String, value: Boolean) {
        if (target == null) return
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                field.setBoolean(target, value)
                return
            } catch (missing: NoSuchFieldException) {
                type = type.superclass
            } catch (ignored: Throwable) {
                return
            }
        }
    }

    /**
     * Drop ad-like entries from one overlay batch.
     *
     * Walks `data.items`, then each entry's `properties`. When those properties
     * do not expose `mainKey`, one nested level (`properties.styleInfo`) is tried,
     * because the two structures differ between scene types and app versions.
     */
    private fun filterItems(data: Any?, tag: String) {
        if (data == null || Config.visible(Config.K_MAP_POI)) return
        val value = read(data, "items")
        if (value !is List<*>) return
        var hidden = 0
        var seen = 0
        for (entry in value) {
            if (entry == null) continue
            seen++
            val props = read(entry, "properties")
            if (props == null) continue

            var holder = props
            var main = asInt(read(holder, "mainKey"))
            if (main == null) {
                val nested = read(holder, "styleInfo")
                if (nested != null) {
                    holder = nested
                    main = asInt(read(holder, "mainKey"))
                }
            }
            if (main == null) continue
            if (main == KEY_RANKING || main == KEY_PROMO) {
                // Only flip a `visible` that is genuinely there; otherwise the write is a no-op
                // and the entry stays, which is the correct outcome for an unknown structure.
                if (read(holder, "visible") != null) {
                    writeBoolean(holder, "visible", false)
                    hidden++
                }
            }
        }
        // One line per batch, and only when something changed or the first batch arrives.
        if (hidden != 0) H.log("amap_poi hidden=" + hidden + " of=" + seen + " tag=" + tag)
    }

    @JvmStatic
    fun install(cl: ClassLoader) {
        // ★ 性能默认：**默认不装**这三个 native 标注 hook（`labels_on` 默认 false）。
        //
        // 理由（用户连续两轮反馈「卡」）：
        //   · 这三个方法位于「每次刷新第三方标注 / POI / 样式点」的热路径上；若高德在
        //     渲染帧里调用它们，我们的 hook 就变成每帧一次 Java→hook→native 往返。
        //   · 地图是 SurfaceView/GL 独立 surface 渲染，`dumpsys gfxinfo` 与
        //     `Choreographer#doFrame` 都**测不到**地图层的真实帧 ⇒ 这类开销在现有
        //     测量手段下是"隐形"的，只能靠默认关掉来消除风险。
        //   · **功能不损失**：地图标注的真正实现是 `libmapclean_labels.so` 在原生层
        //     改样式 protobuf（见 app/native/label_assets.cpp），本类只是"补充过滤"，
        //     注释原文即为「这段不做主力」。
        //
        // 需要恢复时：面板里把 `labels_on` 打开（或直接写 prefs `labels_on=true`）。
        // 保留 `labels_off` 作为"强制关"的兼容键（两者任一为关，就不装）。
        if (!Config.selected(Config.K_LABELS_ON) || Config.selected(Config.K_LABELS_OFF)) {
            H.log("event=labels_off labels_on=" + Config.selected(Config.K_LABELS_ON)
                    + " native_label_hooks=NOT_INSTALLED")
            return
        }
        val scene = H.cls(cl, "com.autonavi.jni.vmap.dsl.VMapSceneWrapper")
        if (scene == null) return

        // NOTE on which lever is correct here.
        //
        // 兴趣点的隐藏由 libmapclean_labels.so 在**原生层**完成（见 app/native/label_assets.cpp）：
        // 它挂住 AAssetManager_open，只截 map_assets/style_*X_Main 这一张样式 protobuf，
        // 用 style::amap() 把兴趣点要素改写成不可见，道路 / 水域 / 地名原样保留。
        // 那才是“去地图标注”的真正实现，由 NativeLabelAssets 开关。
        //
        // 这里曾经改为 hook setProcessLabel / setGlobalProcessLabel 并强行下发 false。
        // 验证下来那是**错的**：processLabel 是引擎的总开关，关掉后路名、城市名
        // 会一起消失（截图里整张图一个文字都没了），与开关承诺的“保留道路与地名”矛盾。
        // 已回退；下面只保留两类无害的补充过滤。

        // 补充一：第三方标注批次里带 mainKey 的推广/榜单条目直接置不可见。
        // 引擎自己画的基础 POI 不走这里，所以这段不做主力，只是不让它们以图钉形式重新出现。
        val labels = H.cls(cl, "com.autonavi.jni.vmap.dsl.MapSceneObjDef\$ThirdLabelInfos")
        val points = H.cls(cl, "com.autonavi.jni.vmap.dsl.MapSceneObjDef\$StylePointInfos")
        if (labels != null) {
            for (method in arrayOf("nativeSetThirdLabelInfo",
                    "nativeSetUpdate3rdLabelsWithPoiData")) {
                H.hookSig(scene, method, "amap_poi_" + method, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        filterItems(chain.getArg(3), method)
                        return chain.proceed()
                    }
                }, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, labels)
            }
        }
        if (points != null) {
            H.hookSig(scene, "nativeSetStylePointInfo", "amap_poi_nativeSetStylePointInfo",
                    object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            filterItems(chain.getArg(3), "nativeSetStylePointInfo")
                            return chain.proceed()
                        }
                    }, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, points)
        }
    }
}
