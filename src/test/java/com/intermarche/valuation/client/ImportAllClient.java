package com.intermarche.valuation.client;

public class ImportAllClient {

    public static void main(String[] args) {
        System.out.println("Importing Stores...");
        StoreImporterClient.main(args);
        System.out.println("Importing Store Groups...");
        StoreGroupImporterClient.main(args);
        System.out.println("Importing Products...");
        ProductImporterClient.main(args);
        System.out.println("Importing Product Families...");
        ProductFamilyImporterClient.main(args);
        System.out.println("Importing Product Categories...");
        ProductCategoryStorageImporterClient.main(args);
        System.out.println("Importing Prices...");
        PriceImporterClient.main(args);
        System.out.println("Importing Offers...");
        OfferImporterClient.main(args);
    }
}
