
        nearbyCellPositioningDataRepository = NearbyCellPositioningDataRepository(
            nearbyCellRepository = NearbyCellRepository(
                nearbyCellLocalDataSource = NearbyCellLocalDataSource(
                    nearbyCellApi = NearbyCellApiImpl(
                        // TODO: check whether it's right to use DEFAULT_SUBSCRIPTION_ID
                        telephonyManager = context.getSystemService(TelephonyManager::class.java)!!
                            .createForSubscriptionId(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                        telephonyScanManager = TelephonyScanManager()
                    ),
                    ioDispatcher = Dispatchers.IO
                )
            ),
            cellPositioningDataRepository = CellPositioningDataRepository(
                cellPositioningDataServerDataSource = CellPositioningDataServerDataSource(
                    cellPositioningDataApi = CellPositioningDataApiImpl(
                        networkLocationServerSetting = networkLocationSettingValue
                    ),
                    ioDispatcher = Dispatchers.IO
                )
            )
        )