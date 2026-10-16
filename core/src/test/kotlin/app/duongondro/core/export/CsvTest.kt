package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun plainFieldsStayAsTheyAre() {
        assertEquals("Dorje Sempa", Csv.field("Dorje Sempa"))
        assertEquals("111111", Csv.field(111_111))
        assertEquals("", Csv.field(null))
        assertEquals("true", Csv.field(true))
    }

    @Test fun quotesWhatRfc4180Needs() {
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
        assertEquals("\"cr\rhere\"", Csv.field("cr\rhere"))
    }

    @Test fun guardsTextASpreadsheetWouldRun() {
        for (bad in listOf("=1+1", "+1", "-1", "@SUM(A1)", "\tx")) assertEquals("'$bad", Csv.field(bad))
        assertEquals("\"'\rx\"", Csv.field("\rx"))
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", Csv.field("=HYPERLINK(\"http://x\",\"y\")"))
        // Only text: a negative number is a number.
        assertEquals("-5", Csv.field(-5))
        assertEquals("a=b", Csv.field("a=b"))
    }

    @Test fun tableHasHeaderAndCrlf() {
        val csv = Csv.table(listOf("Name", "Count"), listOf(listOf("Chenrezig", 108), listOf("Amitabha, long", 0)))
        assertEquals("Name,Count\r\nChenrezig,108\r\n\"Amitabha, long\",0\r\n", csv)
    }

    @Test fun headersAreGuardedToo() {
        assertEquals("'-x\r\n", Csv.table(listOf("-x"), emptyList()))
    }

    @Test(expected = IllegalArgumentException::class) fun refusesARaggedRow() {
        Csv.table(listOf("a", "b"), listOf(listOf("only one")))
    }
}
