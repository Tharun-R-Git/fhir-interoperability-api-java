package com.tharun.fhirdemo.fhir_patient_api;

import ca.uhn.fhir.context.FhirContext;
import jakarta.annotation.PostConstruct;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.DateType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class PatientController {

    @Autowired
    private PatientRepository patientRepository;

    private final FhirContext fhirContext = FhirContext.forR4();

    @PostConstruct
    public void loadSampleData() {
        PatientEntity p1 = new PatientEntity();
        p1.setGivenName("Tharun");
        p1.setFamilyName("Kumar");
        p1.setGender("male");
        p1.setBirthDate("2000-05-15");
        patientRepository.save(p1);

        PatientEntity p2 = new PatientEntity();
        p2.setGivenName("Asha");
        p2.setFamilyName("Reddy");
        p2.setGender("female");
        p2.setBirthDate("1995-11-02");
        patientRepository.save(p2);
    }

    @GetMapping(value = "/fhir/Patient/{id}", produces = "application/json")
    public String getPatient(@PathVariable Long id) {
        PatientEntity entity = patientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Patient not found with id " + id));

        Patient fhirPatient = convertToFhir(entity);

        return fhirContext.newJsonParser()
                .setPrettyPrint(true)
                .encodeResourceToString(fhirPatient);
    }

    @PostMapping(value = "/fhir/Patient", consumes = "application/json", produces = "application/json")
    public String createPatient(@RequestBody String fhirJson) {

        // Parse incoming FHIR JSON into a HAPI FHIR Patient object
        Patient incomingPatient = fhirContext.newJsonParser()
                .parseResource(Patient.class, fhirJson);

        // Convert it into our database entity
        PatientEntity entity = new PatientEntity();
        entity.setGivenName(incomingPatient.getNameFirstRep().getGivenAsSingleString());
        entity.setFamilyName(incomingPatient.getNameFirstRep().getFamily());
        entity.setGender(incomingPatient.getGender() != null
                ? incomingPatient.getGender().toCode()
                : "unknown");
        entity.setBirthDate(incomingPatient.getBirthDateElement() != null
                ? incomingPatient.getBirthDateElement().getValueAsString()
                : null);

        PatientEntity saved = patientRepository.save(entity);

        // Return the saved patient back as FHIR JSON (with its new database ID)
        Patient savedFhir = convertToFhir(saved);
        return fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(savedFhir);
    }

    @GetMapping(value = "/fhir/Patient", produces = "application/json")
    public String getAllPatients() {
        List<PatientEntity> entities = patientRepository.findAll();

        org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
        bundle.setType(org.hl7.fhir.r4.model.Bundle.BundleType.SEARCHSET);

        List<Patient> fhirPatients = entities.stream()
                .map(this::convertToFhir)
                .collect(Collectors.toList());

        fhirPatients.forEach(p -> bundle.addEntry().setResource(p));

        return fhirContext.newJsonParser()
                .setPrettyPrint(true)
                .encodeResourceToString(bundle);
    }

    @PostMapping(value = "/fhir/convert/hl7v2", consumes = "text/plain", produces = "application/json")
    public String convertHl7ToFhir(@RequestBody String hl7Message) {

        String[] lines = hl7Message.split("\r?\n");
        String pidLine = null;

        for (String line : lines) {
            if (line.startsWith("PID")) {
                pidLine = line;
                break;
            }
        }

        if (pidLine == null) {
            throw new RuntimeException("No PID segment found in HL7 message");
        }

        String[] fields = pidLine.split("\\|");

        // PID field indexes (0-based array, but HL7 fields are conventionally 1-based in docs)
        // fields[5] = name (Family^Given), fields[7] = birthdate (YYYYMMDD), fields[8] = gender
        String nameField = fields.length > 5 ? fields[5] : "";
        String birthField = fields.length > 7 ? fields[7] : "";
        String genderField = fields.length > 8 ? fields[8] : "";

        String[] nameParts = nameField.split("\\^");
        String familyName = nameParts.length > 0 ? nameParts[0] : "";
        String givenName = nameParts.length > 1 ? nameParts[1] : "";

        // Convert HL7 date format YYYYMMDD to FHIR format YYYY-MM-DD
        String fhirBirthDate = birthField.length() == 8
                ? birthField.substring(0, 4) + "-" + birthField.substring(4, 6) + "-" + birthField.substring(6, 8)
                : null;

        Enumerations.AdministrativeGender gender =
                "M".equalsIgnoreCase(genderField) ? Enumerations.AdministrativeGender.MALE
                        : "F".equalsIgnoreCase(genderField) ? Enumerations.AdministrativeGender.FEMALE
                          : Enumerations.AdministrativeGender.UNKNOWN;

        // Build the FHIR Patient from parsed HL7 data
        Patient fhirPatient = new Patient();
        fhirPatient.addName().addGiven(givenName).setFamily(familyName);
        fhirPatient.setGender(gender);
        if (fhirBirthDate != null) {
            fhirPatient.setBirthDateElement(new DateType(fhirBirthDate));
        }

        return fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(fhirPatient);
    }

    private Patient convertToFhir(PatientEntity entity) {
        Patient patient = new Patient();
        patient.setId(String.valueOf(entity.getId()));
        patient.addName()
                .addGiven(entity.getGivenName())
                .setFamily(entity.getFamilyName());
        patient.setGender(
                "male".equalsIgnoreCase(entity.getGender())
                        ? Enumerations.AdministrativeGender.MALE
                        : Enumerations.AdministrativeGender.FEMALE
        );
        patient.setBirthDateElement(new DateType(entity.getBirthDate()));
        return patient;
    }
}