/*
 * Copyright (C) 2019 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package ome.tools.hibernate;

import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.parser.SqlParserUtil;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.support.DatabaseMetaDataCallback;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/**
 * Rewrite SQL queries to reverse the effects of {@code @ColumnTransformer} annotations in {@code WHERE} clauses.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.6
 */
public class SqlQueryTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlQueryTransformer.class);

    /* Corresponds to @ColumnTransformer annotation content to which Hibernate may prepend a "this_." prefix inside the comment. */
    private static final String MARKER_COMMENT = "securestring*/";

    /* Pattern for finding a possible WHERE clause in a SQL query. */
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /* Parser configuration corresponding to the SQL dialect of the database. */
    private final SqlParser.Config parserConfiguration;

    /* Cache for consecutive repeats of the same query. */
    private final ThreadLocal<String> transformFrom = new ThreadLocal<>();
    private final ThreadLocal<String> transformTo = new ThreadLocal<>();

    /**
     * Rewrite transformed values away from {@code WHERE} clauses.
     * See {@link SqlQueryTransformer#transformQuery(String)} for usage example.
     * @author m.t.b.carroll@dundee.ac.uk
     * @since 5.5.6
     */
    private class Transformer extends SqlBasicVisitor<SqlNode> {

        private final String original;
        private final StringBuilder transformed;

        /**
         * @param sql the SQL that is to be transformed
         */
        Transformer(String sql) {
            original = sql;
            transformed = new StringBuilder(sql);
        }

        /**
         * @param node a node in the parse tree
         * @return the string index where the given parse node starts
         */
        private int getIndex(SqlNode node) {
            final SqlParserPos parserPosition = node.getParserPosition();
            return SqlParserUtil.lineColToIndex(original, parserPosition.getLineNum(), parserPosition.getColumnNum());
        }

        @Override
        public SqlNode visit(SqlCall call) {
             if (call instanceof SqlSelect) {
                 /* In a SELECT ignore all but the WHERE clause. */
                 final SqlSelect selectCall = (SqlSelect) call;
                 if (selectCall.hasWhere()) {
                     selectCall.getWhere().accept(this);
                 }
                 return null;
             } else if (call instanceof SqlBasicCall) {
                 /* Check for what appears to be the result of a column transformer ... */
                 final SqlBasicCall basicCall = (SqlBasicCall) call;
                 final List<SqlNode> operands = basicCall.getOperandList();
                 if (operands.size() == 2) {
                     final int secondIndex = getIndex(operands.get(1));
                     final int matchIndex = secondIndex - MARKER_COMMENT.length() - 1;
                     if (matchIndex >= 0 && original.startsWith(MARKER_COMMENT, matchIndex)) {
                         /* ... and reverse its effect. */
                         int index = getIndex(operands.get(0));
                         transformed.setCharAt(index, '/');
                         while (++index < matchIndex) {
                             transformed.setCharAt(index, '*');
                         }
                         return null;
                     }
                 }
             }
             return super.visit(call);
        }

        @Override
        public String toString() {
            return transformed.toString();
        }
    }

    /**
     * Convenience constructor for unit testing so that it need not mock a data source.
     * Not for use in production. May be removed without warning.
     */
    public SqlQueryTransformer() {
        this(AnsiSqlDialect.DEFAULT);
    }

    /**
     * Construct a SQL query transformer that parses the SQL dialect appropriate for the given data source.
     * @param dataSource a data source
     * @throws MetaDataAccessException if the metadata about the database could not be accessed from the data source
     */
    public SqlQueryTransformer(DataSource dataSource) throws MetaDataAccessException {
        this((SqlDialect) JdbcUtils.extractDatabaseMetaData(dataSource, new DatabaseMetaDataCallback() {
            @Override
            public Object processMetaData(DatabaseMetaData metadata) {
                return SqlDialectFactoryImpl.INSTANCE.create(metadata);
            }
        }));
    }

    /**
     * Construct a SQL query transformer that parses the given SQL dialect.
     * @param dialect a SQL dialect
     */
    private SqlQueryTransformer(SqlDialect dialect) {
        parserConfiguration = dialect.configureParser(SqlParser.configBuilder()).build();
    }

    /**
     * Reverse the effects of {@code @ColumnTransformer} annotations in {@code WHERE} clauses.
     * @param sql a SQL query
     * @return the transformed query
     */
    public String transformQuery(String sql) {
        final int markerIndex = sql.lastIndexOf(MARKER_COMMENT);
        if (markerIndex == -1 || !WHERE_PATTERN.matcher(sql).region(0, markerIndex).find()) {
            /* There is nothing to transform. */
            return sql;
        }
        /* Check if this query is the one we previously transformed. */
        if (sql.equals(transformFrom.get())) {
            LOGGER.debug("SQL cache hit");
            return transformTo.get();
        } else {
            transformFrom.set(sql);
        }
        LOGGER.debug("SQL to parse: {}", sql);
        /* Quoting "value", etc. works around CALCITE-1785 but may not be robust against plausible SQL queries.
         * TODO: From Hibernate 5 we should try hibernate.auto_quote_keyword if we watch out for HHH-7890. */
        sql = sql.replaceAll("_\\.([a-z]+) as\\b", "_.\"$1\" as");
        try {
            /* Parse and transform the SQL query. */
            final SqlParser parser = SqlParser.create(sql, parserConfiguration);
            final Transformer transformer = new Transformer(sql);
            parser.parseQuery().accept(transformer);
            sql = transformer.toString();
            LOGGER.debug("SQL transformed: {}", sql);
        } catch (SqlParseException spe) {
            LOGGER.warn("failed to parse SQL", spe);
        }
        transformTo.set(sql);
        return sql;
    }
}
