package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a {@link Select} into its top-level select bodies (#862): the statement itself, every
 * branch of a set operation, parentheses unwrapped, plus each CTE body. Subqueries in FROM / WHERE
 * are deliberately not descended into — {@code EXISTS (SELECT * …)} is idiomatic, not a smell.
 */
final class SelectBodies {

    private SelectBodies() {
    }

    static List<PlainSelect> topLevel(Select select) {
        var out = new ArrayList<PlainSelect>();
        if (select == null) {
            return out;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() != null) {
                    collect(item.getSelect(), out);
                }
            }
        }
        collect(select, out);
        return out;
    }

    private static void collect(Select select, List<PlainSelect> out) {
        switch (select) {
            case PlainSelect plain -> out.add(plain);
            case SetOperationList set -> set.getSelects().forEach(branch -> collect(branch, out));
            case ParenthesedSelect parenthesed -> collect(parenthesed.getSelect(), out);
            default -> { /* VALUES, LATERAL, TABLE … carry no select list */ }
        }
    }
}
