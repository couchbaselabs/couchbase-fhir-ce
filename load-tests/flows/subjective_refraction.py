from faker import Faker

from data_entry.complaints import create_complaints_form_with_fake_data, fetch_complaints
from data_entry.history import create_history_form_with_fake_data, fetch_history
from data_entry.subjective import create_subjective_refraction_form_with_fake_data, fetch_subjective_refraction
from resources.practitioner import choose_existing_practitioner
from resources.summary import fhir_patient_sidebar, fhir_patient_summary
from resources.location import choose_existing_location
from resources.encounter import get_encounters_with_details, transfer_encounter_location


fake = Faker()


def subjective_refraction_flow(self):
    print(f"\n-------- user : {self.practitioner_id} --------- [subjective refraction flow start]")

    practitioner_id = choose_existing_practitioner(self.fhir) or self.practitioner_id

    # 1) Run dashboard-style query using the first patient_list config
    get_encounters_with_details(self.fhir)

    # 2) Query sidebar and patient summary data
    fhir_patient_sidebar(self.fhir, self.patient_id, self.encounter_id)
    fhir_patient_summary(self.fhir, self.patient_id)

    # 3) Load subjective refraction and create form with fake data
    fetch_subjective_refraction(self.fhir, self.patient_id)
    create_subjective_refraction_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_subjective_refraction(self.fhir, self.patient_id)

    # 4) Load treatment history form data
    fetch_history(self.fhir, self.patient_id)
    create_history_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_history(self.fhir, self.patient_id)

    # 5) Load complaints form data
    fetch_complaints(self.fhir, self.patient_id)
    create_complaints_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_complaints(self.fhir, self.patient_id)

    # Transfer to next flow
    new_location_id = choose_existing_location(self.fhir)
    if new_location_id and self.encounter_id:
        transfer_encounter_location(self.fhir, self.encounter_id, new_location_id)


