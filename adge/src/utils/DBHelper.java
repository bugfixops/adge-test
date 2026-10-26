package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootMethod;

import java.sql.*;
import java.util.HashMap;
import java.util.HashSet;

public class DBHelper {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Connection connection;
    private Statement statement;

    private HashMap<String, HashSet<String>> resourceIdToMethodMap = new HashMap<String, HashSet<String>>();

    public HashMap<String, HashSet<String>> getResourceIdToMethodMap() {
        return resourceIdToMethodMap;
    }

    public void loadResourceIdToMethodMapFromDB(){
        ResultSet resultSet = null;

        try {
            // 创建 SQL 语句
            String sql = "SELECT * FROM ResourceIdToMethodMap";

            // 执行查询
            resultSet = statement.executeQuery(sql);

            // 遍历结果集并处理数据
            while (resultSet.next()) {
                int id = resultSet.getInt("id");
                int projectId = resultSet.getInt("project_id");
                String resourceId = resultSet.getString("resource_id");
                String methodName = resultSet.getString("method_name");

                if (!resourceIdToMethodMap.containsKey(resourceId)){
                    HashSet<String> methods = new HashSet<String>();
                    methods.add(methodName);
                    resourceIdToMethodMap.put(resourceId, methods);
                }else {
                    resourceIdToMethodMap.get(resourceId).add(methodName);
                }
            }
            logger.info("resourceIdToMethodMap size: " + resourceIdToMethodMap.size());
        } catch (Exception e) {
            logger.error("Error while querying database", e);
        } finally {
            try {
                // 关闭资源
                if (resultSet != null) {
                    resultSet.close();
                }
            } catch (Exception e) {
                logger.error("Error ", e);
            }
        }
    }
    public void loadDB(String dbPath){
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            statement = connection.createStatement();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static DBHelper instance;

    private DBHelper(){

    }

    public static DBHelper getInstance(){
        if (instance == null){
            instance = new DBHelper();
        }
        return instance;
    }

    public ResultSet query(String cmd){
        try {
            return statement.executeQuery(cmd);
        } catch (SQLException exception) {
            exception.printStackTrace();
            return null;
        }
    }

    public ResultSet getSinks(){
        String cmd = "select Units.toString from TaintNodes, Units where TaintNodes.flag=1 and TaintNodes.hash=Units.hash";
        try {
            return statement.executeQuery(cmd);
        } catch (SQLException exception) {
            exception.printStackTrace();
            return null;
        }
    }
}
