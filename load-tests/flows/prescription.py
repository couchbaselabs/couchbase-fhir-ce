from faker import Faker

from data_entry.drugs import create_drug_prescription_form_with_fake_data, fetch_drug_prescription
from data_entry.glass_prescription import create_glass_prescription_form_with_fake_data, fetch_glass_prescription
from data_entry.referral import create_referral_form_with_fake_data, fetch_referral
from data_entry.surgical_note import create_surgical_notes_form_with_fake_data, fetch_surgical_notes
from resources.summary import fhir_patient_sidebar, fhir_patient_summary
from resources.encounter import discharge_encounter, get_encounters_with_details
from resources.practitioner import choose_existing_practitioner


fake = Faker()


def prescription_flow(self):
    print("\n--------- [prescription flow start]")
    practitioner_id = choose_existing_practitioner(self.fhir) or self.practitioner_id
    # 1) Run dashboard-style query using the first patient_list config
    get_encounters_with_details(self.fhir)

    # 2) Query sidebar and patient summary data
    fhir_patient_sidebar(self.fhir, self.patient_id, self.encounter_id)
    fhir_patient_summary(self.fhir, self.patient_id)

    # 3) Load Glass Precription data
    fetch_glass_prescription(self.fhir, self.patient_id)
    create_glass_prescription_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_glass_prescription(self.fhir, self.patient_id)

    # 4) Load Drug Precription form data
    fetch_drug_prescription(self.fhir, self.patient_id)
    create_drug_prescription_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_drug_prescription(self.fhir, self.patient_id)

    # 5) Load Surgical Note form data
    fetch_surgical_notes(self.fhir, self.patient_id)
    create_surgical_notes_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_surgical_notes(self.fhir, self.patient_id)

    # 6) Load Referral form data
    fetch_referral(self.fhir, self.patient_id)
    create_referral_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_referral(self.fhir, self.patient_id)

    # 7) Discharge patient
    discharge_encounter(self.fhir, self.encounter_id)