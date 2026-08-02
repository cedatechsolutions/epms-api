package com.cems.api.service;

import com.cems.api.dto.SurveyResultsResponse;
import com.cems.api.dto.SurveyResultsResponse.CategoryResult;
import com.cems.api.entity.Survey;
import com.cems.api.entity.User;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.RoleName;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Generates the official EXTN-QF-23 Needs Assessment Report as an editable {@code .docx}
 * (spec Module 3 §5), pre-filled from system data.
 *
 * <p><b>Nothing is invented.</b> Narrative sections the system cannot know (Introduction,
 * Methodology, Conclusion, Recommendations) are emitted as clearly marked placeholder paragraphs
 * for the project leader to complete — this is an explicit acceptance criterion. Every number in
 * the document comes from the finalized assessment results.
 */
@Service
public class Qf23ReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy")
            .withZone(ZoneId.systemDefault());

    /** Marker wrapped around every paragraph the system deliberately does not fill in. */
    private static final String PLACEHOLDER_PREFIX = "[TO BE COMPLETED — ";

    private final SurveyRepository surveyRepository;
    private final UserRepository userRepository;
    private final SurveyResultsService resultsService;

    public Qf23ReportService(SurveyRepository surveyRepository,
            UserRepository userRepository,
            SurveyResultsService resultsService) {
        this.surveyRepository = surveyRepository;
        this.userRepository = userRepository;
        this.resultsService = resultsService;
    }

    /** Optional admin overrides for the signatory block; any null falls back to the role holder. */
    public record SignatoryOverrides(String preparedBy, String notedBy, String recommendingApproval,
            String approvedBy) {

        public static SignatoryOverrides empty() {
            return new SignatoryOverrides(null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public byte[] generate(String surveyId, SignatoryOverrides overrides) {
        Survey survey = surveyRepository.findByIdAndDeletedAtIsNull(surveyId)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));
        SurveyResultsResponse results = resultsService.getResults(surveyId);

        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            writeHeader(document, survey, results);
            writeNarrativeSection(document, "I. Introduction",
                    "describe the background and context of this needs assessment");
            writeObjectives(document, results);
            writeNarrativeSection(document, "III. Methodology",
                    "describe how the assessment was conducted (sampling, data-gathering procedure, "
                            + "instruments used) beyond the survey facts listed above");
            writeRespondents(document, results);
            writeResultsAndDiscussion(document, results);
            writeNarrativeSection(document, "VI. Conclusion",
                    "state the conclusions drawn from the ranked needs above");
            writeNarrativeSection(document, "VII. Recommendations",
                    "state the recommended extension programs and next steps");
            writeSignatories(document, overrides);

            document.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate the EXTN-QF-23 report.", ex);
        }
    }

    // --- sections ---

    private void writeHeader(XWPFDocument document, Survey survey, SurveyResultsResponse results) {
        centeredHeading(document, "CAVITE STATE UNIVERSITY – BACOOR CAMPUS", 12, true);
        centeredHeading(document, "Extension Services", 11, false);
        centeredHeading(document, "NEEDS ASSESSMENT REPORT", 14, true);
        centeredHeading(document, "(EXTN-QF-23)", 10, false);

        blank(document);
        labelled(document, "Title of Activity", survey.getTitle());
        labelled(document, "Date Conducted", formatDateRange(survey));
        labelled(document, "Place Conducted", results.communityName());
        labelled(document, "Total Respondents", String.valueOf(results.totalResponses()));
    }

    private void writeObjectives(XWPFDocument document, SurveyResultsResponse results) {
        heading(document, "II. Objectives");
        // The one objective the system can state factually.
        bullet(document, "To identify and rank the priority needs of " + results.communityName()
                + " through a sex-disaggregated community survey.");
        placeholder(document, "add any further objectives specific to this assessment");
    }

    /**
     * Respondents table, sex-disaggregated (spec §5). Residents are the actual survey respondents.
     * The system does not capture a resident/personnel distinction, so the personnel row is left as
     * a placeholder rather than fabricating zeroes.
     */
    private void writeRespondents(XWPFDocument document, SurveyResultsResponse results) {
        heading(document, "IV. Respondents");

        XWPFTable table = document.createTable(4, 4);
        headerRow(table.getRow(0), "Category of Respondents", "Female", "Male", "Total");

        long female = results.femaleResponses();
        long male = results.maleResponses();
        long total = results.totalResponses();

        fillRow(table.getRow(1), "Community Residents",
                String.valueOf(female), String.valueOf(male), String.valueOf(total));
        fillRow(table.getRow(2), "University Personnel",
                "—", "—", "—");
        fillRow(table.getRow(3), "TOTAL",
                String.valueOf(female), String.valueOf(male), String.valueOf(total));

        blank(document);
        placeholder(document, "the system records community respondents only; if university personnel "
                + "took part, complete the 'University Personnel' row and adjust the TOTAL");
        note(document, "Note: respondents who selected \"prefer not to say\" are included in the Total "
                + "but in neither the Female nor Male column (" + results.undisclosedSexResponses()
                + " respondent(s)).");
    }

    private void writeResultsAndDiscussion(XWPFDocument document, SurveyResultsResponse results) {
        heading(document, "V. Results and Discussion");

        if (results.categories().isEmpty()) {
            placeholder(document, "no rating answers were collected, so no needs could be ranked");
            return;
        }

        paragraph(document, "The table below ranks the identified needs by weighted average score "
                + "(1–5 scale), with respondent counts disaggregated by sex.");
        blank(document);

        List<CategoryResult> categories = results.categories();
        XWPFTable table = document.createTable(categories.size() + 1, 7);
        headerRow(table.getRow(0), "Rank", "Need Category", "Weighted Average",
                "Priority", "Respondents", "Female", "Male");

        for (int index = 0; index < categories.size(); index++) {
            CategoryResult category = categories.get(index);
            fillRow(table.getRow(index + 1),
                    String.valueOf(category.rank()),
                    category.needCategoryName(),
                    category.avgScore().toPlainString(),
                    capitalize(category.priority()),
                    String.valueOf(category.responseCount()),
                    String.valueOf(category.femaleCount()),
                    String.valueOf(category.maleCount()));
        }

        blank(document);
        CategoryResult top = categories.get(0);
        paragraph(document, "The highest-ranked need is " + top.needCategoryName()
                + " with a weighted average of " + top.avgScore().toPlainString()
                + " (" + capitalize(top.priority()) + " priority).");
        placeholder(document, "discuss what these results mean for the community and why these needs emerged");
    }

    private void writeSignatories(XWPFDocument document, SignatoryOverrides overrides) {
        blank(document);
        heading(document, "Signatories");

        XWPFTable table = document.createTable(4, 2);
        signatoryRow(table.getRow(0), "Prepared by:",
                resolve(overrides.preparedBy(), RoleName.FACULTY));
        signatoryRow(table.getRow(1), "Noted by:",
                resolve(overrides.notedBy(), RoleName.EXTENSION_COORDINATOR));
        signatoryRow(table.getRow(2), "Recommending Approval:",
                resolve(overrides.recommendingApproval(), RoleName.CAMPUS_EXTENSION_COORDINATOR));
        signatoryRow(table.getRow(3), "Approved:",
                resolve(overrides.approvedBy(), RoleName.CAMPUS_ADMIN));
    }

    /** An explicit override wins; otherwise the first active holder of the role; otherwise a blank line. */
    private String resolve(String override, RoleName role) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return userRepository.findByRoles_NameAndActiveTrueAndDeletedAtIsNull(role.code()).stream()
                .findFirst()
                .map(this::displayName)
                .orElse("________________________");
    }

    private String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isBlank() ? user.getEmail() : name;
    }

    private String formatDateRange(Survey survey) {
        if (survey.getOpensAt() == null && survey.getClosesAt() == null) {
            return DATE.format(survey.getCreatedAt());
        }
        String from = survey.getOpensAt() == null ? DATE.format(survey.getCreatedAt())
                : DATE.format(survey.getOpensAt());
        if (survey.getClosesAt() == null) {
            return from;
        }
        return from + " – " + DATE.format(survey.getClosesAt());
    }

    // --- docx primitives ---

    private void centeredHeading(XWPFDocument document, String text, int size, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(size);
    }

    private void heading(XWPFDocument document, String text) {
        blank(document);
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(12);
    }

    private void paragraph(XWPFDocument document, String text) {
        XWPFRun run = document.createParagraph().createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void bullet(XWPFDocument document, String text) {
        XWPFRun run = document.createParagraph().createRun();
        run.setText("• " + text);
        run.setFontSize(11);
    }

    private void note(XWPFDocument document, String text) {
        XWPFRun run = document.createParagraph().createRun();
        run.setText(text);
        run.setItalic(true);
        run.setFontSize(9);
    }

    /**
     * A clearly marked gap for a human to fill. The system never guesses narrative content
     * (spec Module 3 §5 AC).
     */
    private void placeholder(XWPFDocument document, String instruction) {
        XWPFRun run = document.createParagraph().createRun();
        run.setText(PLACEHOLDER_PREFIX + instruction.toUpperCase(java.util.Locale.ROOT) + "]");
        run.setItalic(true);
        run.setBold(true);
        run.setFontSize(11);
    }

    private void writeNarrativeSection(XWPFDocument document, String title, String instruction) {
        heading(document, title);
        placeholder(document, instruction);
    }

    private void labelled(XWPFDocument document, String label, String value) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setText(label + ": ");
        labelRun.setBold(true);
        labelRun.setFontSize(11);
        XWPFRun valueRun = paragraph.createRun();
        valueRun.setText(value == null ? "" : value);
        valueRun.setFontSize(11);
    }

    private void blank(XWPFDocument document) {
        document.createParagraph().createRun().setText("");
    }

    private void headerRow(XWPFTableRow row, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (row.getCell(index) == null) {
                row.addNewTableCell();
            }
            row.getCell(index).removeParagraph(0);
            XWPFRun run = row.getCell(index).addParagraph().createRun();
            run.setText(values[index]);
            run.setBold(true);
            run.setFontSize(10);
        }
    }

    private void fillRow(XWPFTableRow row, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (row.getCell(index) == null) {
                row.addNewTableCell();
            }
            row.getCell(index).removeParagraph(0);
            XWPFRun run = row.getCell(index).addParagraph().createRun();
            run.setText(values[index]);
            run.setFontSize(10);
        }
    }

    private void signatoryRow(XWPFTableRow row, String label, String name) {
        row.getCell(0).removeParagraph(0);
        XWPFRun labelRun = row.getCell(0).addParagraph().createRun();
        labelRun.setText(label);
        labelRun.setBold(true);
        labelRun.setFontSize(10);

        if (row.getCell(1) == null) {
            row.addNewTableCell();
        }
        row.getCell(1).removeParagraph(0);
        XWPFRun nameRun = row.getCell(1).addParagraph().createRun();
        nameRun.setText(name);
        nameRun.setFontSize(10);
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
