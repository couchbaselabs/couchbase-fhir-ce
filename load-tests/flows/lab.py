from faker import Faker
from data_entry.ascan import create_ascan_form_with_fake_data, fetch_ascan
from data_entry.free_text import create_free_text_form_with_fake_data, fetch_free_text
from data_entry.lab import create_lab_form_with_fake_data, fetch_lab
from resources.practitioner import choose_existing_practitioner
from resources.location import choose_existing_location
from resources.encounter import get_encounters_with_details, transfer_encounter_location
from resources.summary import fhir_patient_sidebar, fhir_patient_summary

fake = Faker()

def lab_flow(self):
    print("\n--------- [lab flow start]")
    practitioner_id = choose_existing_practitioner(self.fhir) or self.practitioner_id
    # 1) Run dashboard-style query using the first patient_list config
    get_encounters_with_details(self.fhir)

    # 2) Query sidebar and patient summary data
    fhir_patient_sidebar(self.fhir, self.patient_id, self.encounter_id)
    fhir_patient_summary(self.fhir, self.patient_id)

    # 3) Load Lab form data
    fetch_lab(self.fhir, self.patient_id)
    create_lab_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_lab(self.fhir, self.patient_id)

    # 4) Load Free text form data
    fetch_free_text(self.fhir, self.patient_id)
    create_free_text_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_free_text(self.fhir, self.patient_id)

    # 5) Load Ascan form data
    fetch_ascan(self.fhir, self.patient_id)
    create_ascan_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_ascan(self.fhir, self.patient_id)

    # 6) Transfer patient to Doctor flow
    new_location_id = choose_existing_location(self.fhir)
    if new_location_id and self.encounter_id:
        transfer_encounter_location(self.fhir, self.encounter_id, new_location_id)

