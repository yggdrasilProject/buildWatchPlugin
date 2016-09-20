package ru.linachan.monitor.db;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

public class DataBaseWrapper {

    private MongoClient dbClient;
    private MongoDatabase dbInstance;

    public DataBaseWrapper(String dbUri, String dbName) {
        dbClient = new MongoClient(new MongoClientURI(dbUri));
        dbInstance = dbClient.getDatabase(dbName);
    }

    public MongoDatabase getDataBase() {
        return dbInstance;
    }

    public MongoCollection<Document> getCollection(String collection) {
        return dbInstance.getCollection(collection);
    }

    public void close() {
        dbClient.close();
    }
}