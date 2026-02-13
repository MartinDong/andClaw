plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("install_time_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
