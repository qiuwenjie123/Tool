package bean;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ProcessingInstruction;
import org.w3c.dom.Text;

public class MyBatisGenerator {
    public static void main(String[] args) throws ParserConfigurationException {
        String createTableStatement =
            "CREATE TABLE `users` (\n" + "  `id` INT(11) NOT NULL AUTO_INCREMENT COMMENT '用户ID',\n"
                + "  `user_name` VARCHAR(50) DEFAULT NULL COMMENT '用户名',\n"
                + "  `pass_word` VARCHAR(50) DEFAULT NULL COMMENT '密码',\n" + "  PRIMARY KEY (`user_name`)\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='用户表';";

        String tableName = parseTableName(createTableStatement);
        List<ColumnDefinition> columnDefinitions = parseColumnDefinitions(createTableStatement);

        generatePOClass(tableName, columnDefinitions);
        generateMyBatisXML(tableName, columnDefinitions);
    }

    private static String parseTableName(String createTableStatement) {
        // 使用正则表达式或其他方式提取表名
        // 这里只是简单地假设表名出现在CREATE TABLE语句的第二个反引号中
        int firstBacktickIndex = createTableStatement.indexOf('`');
        int secondBacktickIndex = createTableStatement.indexOf('`', firstBacktickIndex + 1);
        return createTableStatement.substring(firstBacktickIndex + 1, secondBacktickIndex);
    }

    private static List<ColumnDefinition> parseColumnDefinitions(String createTableStatement) {
        int startIndex = createTableStatement.indexOf('(') + 1;
        int endIndex = createTableStatement.lastIndexOf(')');
        String columnDefinitionsString = createTableStatement.substring(startIndex, endIndex);

        // 切割列定义字符串，并解析每个列的名称、类型和注释
        String[] columnDefinitionStrings = columnDefinitionsString.split(",\n");
        List<ColumnDefinition> columnDefinitions = new ArrayList<>();
        for (String columnDefinitionString : columnDefinitionStrings) {
            // 处理注释
            String columnComment = getColumnComment(columnDefinitionString);

            // todo 主键处理
            if (isPrimaryKey(columnDefinitionString)) {
                Optional<ColumnDefinition> first = columnDefinitions.stream()
                    .filter(param -> param.getColumnName().equals(getPrimaryKeyName(columnDefinitionString)))
                    .findFirst();
                first.get().setPrimaryKey(true);
                continue;
            }

            String[] parts = columnDefinitionString.trim().split(" ");
            String columnName = parts[0].replace("`", "");
            String columnType = parts[1];
            columnDefinitions.add(new ColumnDefinition(columnName, columnType, columnComment));
        }

        return columnDefinitions;
    }

    private static String getColumnComment(String columnDefinitionString) {
        // 使用正则表达式提取注释信息
        String commentPattern = "COMMENT\\s+'([^']+)'";
        Pattern pattern = Pattern.compile(commentPattern);
        Matcher matcher = pattern.matcher(columnDefinitionString);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static boolean isPrimaryKey(String columnDefinitionString) {
        return columnDefinitionString.toUpperCase(Locale.ROOT).trim().startsWith("PRIMARY KEY");
    }

    private static String getPrimaryKeyName(String columnDefinitionString) {
        return columnDefinitionString
            .substring(columnDefinitionString.indexOf("("), columnDefinitionString.indexOf(")"))
            .replaceAll("[`\\(]", "");
    }

    private static void generatePOClass(String tableName, List<ColumnDefinition> columnDefinitions) {
        StringBuilder poClassBuilder = new StringBuilder();
        poClassBuilder.append("public class ").append(capitalizeFirstLetter(tableName)).append(" {\n\n");

        // 生成成员变量和注释
        for (ColumnDefinition columnDefinition : columnDefinitions) {
            poClassBuilder.append("  /**\n");
            poClassBuilder.append("   * ").append(columnDefinition.getColumnComment()).append("\n");
            poClassBuilder.append("   */\n");
            poClassBuilder.append("  private ").append(getJavaType(columnDefinition.getColumnType())).append(" ")
                .append(convertToCamelCase(columnDefinition.getColumnName())).append(";\n");
        }

        poClassBuilder.append("\n");

        // 生成构造方法
        poClassBuilder.append("  public ").append(capitalizeFirstLetter(tableName)).append("() {\n")
            .append("    // 默认构造方法\n").append("  }\n\n");

        poClassBuilder.append("}");

        String poClassContent = poClassBuilder.toString();
        String poFileName = capitalizeFirstLetter(tableName) + ".java";
        writeToFile(poFileName, poClassContent);
    }

    private static void generateMyBatisXML(String tableName, List<ColumnDefinition> columnDefinitions)
        throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.newDocument();
        // 创建 DOCTYPE 声明
        String doctypeDeclaration =
            "-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"";
        ProcessingInstruction doctype =
            document.createProcessingInstruction("DOCTYPE", "mapper PUBLIC \"" + doctypeDeclaration);
        // 添加 DOCTYPE 声明到 Document 对象
        document.appendChild(doctype);

        String poClassName = capitalizeFirstLetter(tableName);
        Element rootElement = document.createElement("mapper");
        rootElement.setAttribute("namespace", tableName + "Mapper");

        Element resultMapElement = document.createElement("resultMap");
        resultMapElement.setAttribute("id", "BaseResultMap");
        resultMapElement.setAttribute("type", poClassName);

        // 生成<result>元素
        for (ColumnDefinition columnDefinition : columnDefinitions) {
            Element resultElement;
            if (columnDefinition.isPrimaryKey()) {
                resultElement = document.createElement("id");
            } else {
                resultElement = document.createElement("result");
            }
            resultElement.setAttribute("column", columnDefinition.getColumnName());
            resultElement.setAttribute("property", columnDefinition.getColumnName());
            resultMapElement.appendChild(resultElement);
        }

        rootElement.appendChild(resultMapElement);

        // 获取主键列名
        String primaryKeyColumnName = findPrimaryKeyColumnName(columnDefinitions);

        // 生成selectByPrimaryKey语句
        Element selectByPrimaryKeyElement = document.createElement("select");
        selectByPrimaryKeyElement.setAttribute("id", "selectByPrimaryKey");
        selectByPrimaryKeyElement.setAttribute("parameterType", "java.lang.Integer");
        selectByPrimaryKeyElement.setAttribute("resultMap", "BaseResultMap");
        selectByPrimaryKeyElement.appendChild(document.createTextNode("\n"));
        selectByPrimaryKeyElement.appendChild(document.createTextNode("SELECT * FROM " + tableName + " WHERE "
            + primaryKeyColumnName + " = #{" + convertToCamelCase(primaryKeyColumnName) + "}"));
        rootElement.appendChild(selectByPrimaryKeyElement);

        // 生成insert语句
        Element insertElement = document.createElement("select");
        insertElement.setAttribute("id", "insert");
        insertElement.setAttribute("parameterType", poClassName);
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("    INSERT INTO ").append(tableName).append(" (\n");
        // 生成插入语句的列部分
        for (int i = 0; i < columnDefinitions.size(); i++) {
            ColumnDefinition columnDefinition = columnDefinitions.get(i);
            xmlBuilder.append("      ").append(columnDefinition.getColumnName());
            if (i < columnDefinitions.size() - 1) {
                xmlBuilder.append(",");
            }
            xmlBuilder.append("\n");
        }
        xmlBuilder.append("    ) VALUES (\n");
        // 生成插入语句的值部分
        for (int i = 0; i < columnDefinitions.size(); i++) {
            ColumnDefinition columnDefinition = columnDefinitions.get(i);
            xmlBuilder.append("      #{").append(convertToCamelCase(columnDefinition.getColumnName())).append("}");
            if (i < columnDefinitions.size() - 1) {
                xmlBuilder.append(",");
            }
            xmlBuilder.append("\n");
        }
        xmlBuilder.append("    )\n");
        insertElement.appendChild(document.createTextNode("\n"));
        insertElement.appendChild(document.createTextNode(xmlBuilder.toString()));
        rootElement.appendChild(insertElement);

        // 生成updateByPrimaryKey语句
        Element updateByPrimaryKeyElement = document.createElement("update");
        updateByPrimaryKeyElement.setAttribute("id", "updateByPrimaryKey");
        updateByPrimaryKeyElement.setAttribute("parameterType", poClassName);

        Element setElement = document.createElement("set");

        for (ColumnDefinition columnDefinition : columnDefinitions) {
            String columnName = columnDefinition.getColumnName();

            Element ifElement = document.createElement("if");
            ifElement.setAttribute("test", convertToCamelCase(columnName) + " != null");

            String updateIfNotNullStatement = String.format("%s = #{%s},", columnName, convertToCamelCase(columnName));
            Text updateIfNotNullText = document.createTextNode(updateIfNotNullStatement);

            ifElement.appendChild(updateIfNotNullText);
            setElement.appendChild(ifElement);
        }

        updateByPrimaryKeyElement.appendChild(setElement);
        updateByPrimaryKeyElement.appendChild(document.createTextNode(
            "WHERE " + primaryKeyColumnName + " = #{" + convertToCamelCase(primaryKeyColumnName) + "}"));

        rootElement.appendChild(updateByPrimaryKeyElement);

        // 生成deleteByPrimaryKey语句
        Element deleteByPrimaryKeyElement = document.createElement("delete");
        deleteByPrimaryKeyElement.setAttribute("id", "deleteByPrimaryKey");
        deleteByPrimaryKeyElement.setAttribute("parameterType", "java.lang.Integer");
        selectByPrimaryKeyElement.appendChild(document.createTextNode("\n"));
        deleteByPrimaryKeyElement.appendChild(document.createTextNode("DELETE FROM " + tableName + " WHERE "
            + primaryKeyColumnName + " = #{" + convertToCamelCase(primaryKeyColumnName) + "}"));
        rootElement.appendChild(deleteByPrimaryKeyElement);

        document.appendChild(rootElement);

        try {
            // 创建Transformer对象，用于格式化输出
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty("indent", "yes");
            transformer.setOutputProperty("method", "xml");

            // 将XML内容写入文件
            String xmlFileName = tableName + "Mapper.xml";
            File outputFile = new File(xmlFileName);
            DOMSource source = new DOMSource(document);
            FileWriter writer = new FileWriter(outputFile);
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Document createXmlDocument() throws ParserConfigurationException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.newDocument();
        } catch (Exception e) {
            throw e;
        }
    }

    private static String getJavaType(String columnType) {
        // 根据MySQL类型返回对应的Java类型
        // 这里只是简单地映射了几个常见类型
        if (columnType.startsWith("int")) {
            return "int";
        } else if (columnType.startsWith("varchar") || columnType.startsWith("text")) {
            return "String";
        } else if (columnType.startsWith("timestamp") || columnType.startsWith("datetime")) {
            return "java.util.Date";
        }

        return "Object";
    }

    private static String findPrimaryKeyColumnName(List<ColumnDefinition> columnDefinitions) {
        Optional<ColumnDefinition> first =
            columnDefinitions.stream().filter(ColumnDefinition::isPrimaryKey).findFirst();
        // 如果没有找到带有"key"或"Key"后缀的列名，则返回默认的主键列名
        if (first.isPresent()) {
            return first.get().getColumnName();
        }

        return "id";
    }

    public static String convertToCamelCase(String input) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (int i = 0; i < input.length(); i++) {
            char currentChar = input.charAt(i);

            if (currentChar == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(currentChar));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(currentChar));
                }
            }
        }

        return result.toString();
    }

    private static String capitalizeFirstLetter(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    private static void writeToFile(String fileName, String content) {
        try {
            FileWriter writer = new FileWriter(new File(fileName));
            writer.write(content);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ColumnDefinition {
        private final String columnName;
        private final String columnType;

        private String columnComment;

        private boolean primaryKey;

        public ColumnDefinition(String columnName, String columnType) {
            this.columnName = columnName;
            this.columnType = columnType;
        }

        public ColumnDefinition(String columnName, String columnType, String columnComment) {
            this.columnName = columnName;
            this.columnType = columnType;
            this.columnComment = columnComment;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getColumnType() {
            return columnType;
        }

        public String getColumnComment() {
            return columnComment;
        }

        public void setColumnComment(String columnComment) {
            this.columnComment = columnComment;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(boolean primaryKey) {
            this.primaryKey = primaryKey;
        }
    }
}
