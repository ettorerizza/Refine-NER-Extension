package org.freeyourmetadata.ner.operations;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang.ArrayUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONWriter;

import com.google.refine.history.Change;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.changes.CellAtRow;
import com.google.refine.model.changes.ColumnAdditionChange;
import com.google.refine.model.changes.ColumnRemovalChange;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.Pool;

/**
 * A change resulting from named-entity recognition
 * @author Ruben Verborgh
 */
public class NERChange implements Change {
    private final int columnIndex;
    private final String[] serviceNames;
    private final String[][][] extractedTerms;
    private final List<Integer> addedRowIds;
    
    /**
     * Creates a new <tt>NERChange</tt>
     * @param columnIndex The index of the column used for named-entity recognition
     * @param serviceNames The names of the used services
     * @param extractedTerms The extracted terms per row and service
     */
    public NERChange(final int columnIndex, final String[] serviceNames, final String[][][] extractedTerms) {
        this.columnIndex = columnIndex;
        this.serviceNames = serviceNames;
        this.extractedTerms = extractedTerms;
        this.addedRowIds = new ArrayList<Integer>();
    }

    /** {@inheritDoc} */
    @Override
    public void apply(final Project project) {
        synchronized(project) {
            final int[] cellIndexes = createColumns(project);
            insertValues(project, cellIndexes);
            project.update();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void revert(final Project project) {
        synchronized(project) {
            deleteRows(project);
            deleteColumns(project);
            project.update();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void save(final Writer writer, final Properties options) throws IOException {
        final JSONWriter json = new JSONWriter(writer);
        try {
            /* Change object */
            json.object();
            json.key("column"); json.value(columnIndex);
            json.key("services"); JSONUtilities.writeStringArray(json, serviceNames);
            json.key("terms");
            /* Extracted terms nested array */
            {
                /* Rows array */
                json.array();
                for (final String[][] row : extractedTerms) {
                    /* Services array */
                    json.array();
                    /* Service results array */
                    for (final String[] terms : row)
                        JSONUtilities.writeStringArray(json, terms);
                    json.endArray();
                }
                json.endArray();
            }
            json.key("addedRows");
            /* Added row numbers array */
            {
                json.array();
                for (Integer addedRowId : addedRowIds)
                    json.value(addedRowId.intValue());
                json.endArray();
            }
            json.endObject();
        }
        catch (JSONException error) {
            throw new IOException(error);
        }
    }
    
    /**
     * Create a <tt>NERChange</tt> from a configuration reader
     * @param reader The reader
     * @param pool (unused but required, since this method is called through reflection)
     * @return A new <tt>NERChange</tt>
     * @throws Exception If the configuration is in an unexpected format
     */
    static public Change load(LineNumberReader reader, Pool pool) throws Exception {
        /* Parse JSON line */
        final JSONTokener tokener = new JSONTokener(reader.readLine());
        final JSONObject changeJson = (JSONObject)tokener.nextValue();
        
        /* Simple properties */
        final int columnIndex = changeJson.getInt("column");
        final String[] serviceNames = JSONUtilities.getStringArray(changeJson, "services");
        
        /* Extracted terms nested array */
        final JSONArray extractedTermsJson = changeJson.getJSONArray("terms");
        final String[][][] extractedTerms = new String[extractedTermsJson.length()][][];
        /* Rows array */
        for (int i = 0; i < extractedTerms.length; i++) {
            /* Services array */
            final JSONArray servicesJson = extractedTermsJson.getJSONArray(i);
            final String[][] services = extractedTerms[i] = new String[servicesJson.length()][];
            for (int j = 0; j < services.length; j++) {
                /* Service results array */
                final JSONArray termsJson = servicesJson.getJSONArray(j);
                final String[] terms = services[j] = new String[termsJson.length()];
                for (int k = 0; k < terms.length; k++)
                    terms[k] = termsJson.getString(k);
            }
        }
        
        /* Reconstruct change object */
        final NERChange change = new NERChange(columnIndex, serviceNames, extractedTerms);
        for (final int addedRowId : JSONUtilities.getIntArray(changeJson, "addedRows"))
            change.addedRowIds.add(addedRowId);
        return change;
    }
    
    /**
     * Create the columns where the named entities will be stored
     * @param project The project
     * @return The cell indexes of the created columns
     */
    protected int[] createColumns(final Project project) {
        // Create empty cells that will populate each row
        final int rowCount = project.rows.size();
        final ArrayList<CellAtRow> emptyCells = new ArrayList<CellAtRow>(rowCount);
        for (int r = 0; r < rowCount; r++)
            emptyCells.add(new CellAtRow(r, null));
        
        // Create rows
        final int[] cellIndexes = new int[serviceNames.length];
        for (int c = 0; c < serviceNames.length; c++) {
            final CustomColumnAdditionChange change
                  = new CustomColumnAdditionChange(serviceNames[c], columnIndex + c, emptyCells);
            change.apply(project);
            cellIndexes[c] = change.getCellIndex();
        }
        // Return cell indexes of created rows
        return cellIndexes;
    }
    
    /**
     * Delete the columns where the named entities have been stored
     * @param project The project
     */
    protected void deleteColumns(final Project project) {
        for (int i = 0; i < serviceNames.length; i++)
            new ColumnRemovalChange(columnIndex).apply(project);
    }

    /**
     * Insert the extracted terms into rows with the specified cell indexes
     * @param project The project
     * @param cellIndexes The cell indexes of the rows that will contain the terms
     */
    protected void insertValues(final Project project, final int[] cellIndexes) {
        final List<Row> rows = project.rows;
        // Make sure there are rows
        if (rows.isEmpty())
            return;
        
        // Make sure all rows have enough cells, creating new ones as necessary
        final int maxCellIndex = Collections.max(Arrays.asList(ArrayUtils.toObject(cellIndexes)));
        final int minRowSize = maxCellIndex + 1;
        for (final Row row : rows)
            while (row.cells.size() < minRowSize)
                row.cells.add(null);
        
        // Add the extracted terms to all rows, creating new ones as necessary
        int rowNumber = 0;
        addedRowIds.clear();
        for (final String[][] serviceTerms : extractedTerms) {
            // Determine the maximum number of terms per service
            int maxTerms = 0;
            for (final String[] terms : serviceTerms)
                maxTerms = Math.max(maxTerms, terms.length);
            // Skip this row if no terms were found
            if (maxTerms == 0) {
                rowNumber++;
                continue;
            }
            // Create new blank rows if terms don't fit on a single line
            for (int i = 1; i < maxTerms; i++) {
                final Row termRow = new Row(minRowSize);
                final int termRowId = rowNumber + i;
                for (int j = 0; j < minRowSize; j++)
                    termRow.cells.add(null);
                rows.add(termRowId, termRow);
                addedRowIds.add(termRowId);
            }
            // Place all terms
            for (int c = 0; c < serviceTerms.length; c++)
                for (int r = 0; r < serviceTerms[c].length; r++)
                    rows.get(rowNumber + r).cells.set(cellIndexes[c], new Cell(serviceTerms[c][r], null));
            // Advance to the next original row
            rowNumber += maxTerms;
        }
    }
    
    /**
     * Delete rows that were added to contain extracted terms
     * @param project The project
     */
    protected void deleteRows(final Project project) {
        final List<Row> rows = project.rows;
        // Traverse rows IDs in reverse, from high to low,
        // to avoid index shifts as rows get deleted.
        for (int i = addedRowIds.size() - 1; i >= 0; i--) {
            final int addedRowId = addedRowIds.get(i);
            if (addedRowId >= rows.size())
                throw new IndexOutOfBoundsException(String.format("Needed to remove row %d, "
                                + "but only %d rows were available.", addedRowId, rows.size()));
            rows.remove(addedRowId);
        }
        addedRowIds.clear();
    }
    
    /**
     * Subclass of <tt>ColumnAdditionChange</tt>
     * that provides access to the cell index of the created column
     */
    protected static class CustomColumnAdditionChange extends ColumnAdditionChange {
        /**
         * Create a new <tt>CustomColumnAdditionChange</tt>
         * @param columnName The column name
         * @param columnIndex The column index
         * @param newCells The new cells
         */
        public CustomColumnAdditionChange(final String columnName, final int columnIndex,
                                          final List<CellAtRow> newCells) {
            super(columnName, columnIndex, newCells);
        }
        
        /**
         * Gets the cell index of the created column
         * @return The cell index
         */
        public int getCellIndex() {
            if (_newCellIndex < 0)
                throw new IllegalStateException("The cell index has not yet been set.");
            return _newCellIndex;
        }
    }
}
