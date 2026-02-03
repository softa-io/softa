package io.softa.starter.designer.ddl;

/**
 * DDL Factory for creating DDL instance.
 */
public class DDLFactory {

    public static DDLInterface getInstance() {
        return new MySQLDDL();
    }

}
