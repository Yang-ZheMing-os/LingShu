package com.lingshu.feature.guide.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lingshu.feature.guide.R

enum class GuidePage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
) {
    BRAND_INTRO(
        iconRes = R.drawable.ic_guide_brand,
        titleRes = R.string.guide_brand_title,
        descriptionRes = R.string.guide_brand_description
    ),
    CORE_ABILITY(
        iconRes = R.drawable.ic_guide_core,
        titleRes = R.string.guide_core_title,
        descriptionRes = R.string.guide_core_description
    ),
    VOICE_ABILITY(
        iconRes = R.drawable.ic_guide_voice,
        titleRes = R.string.guide_voice_title,
        descriptionRes = R.string.guide_voice_description
    ),
    PHONE_CONTROL(
        iconRes = R.drawable.ic_guide_control,
        titleRes = R.string.guide_control_title,
        descriptionRes = R.string.guide_control_description
    ),
    PRIVACY_PROMISE(
        iconRes = R.drawable.ic_guide_privacy,
        titleRes = R.string.guide_privacy_title,
        descriptionRes = R.string.guide_privacy_description
    );

    companion object {
        val pages = values().toList()
        val pageCount = values().size
    }
}
