from faker import Faker

from data_entry.pgp import create_pgp_form_with_fake_data, fetch_pgp
from resources.practitioner import choose_existing_practitioner
from resources.encounter import get_encounters_with_details, transfer_encounter_location
from resources.location import choose_existing_location
from resources.summary import fhir_patient_sidebar, fhir_patient_summary

fake = Faker()

def ppg_flow(self):
    print("\n--------- [pgp flow start]")
    practitioner_id = choose_existing_practitioner(self.fhir) or self.practitioner_id
    # 1) Run dashboard-style query using the first patient_list config
    get_encounters_with_details(self.fhir)

    # 2) Query sidebar and patient summary data
    fhir_patient_sidebar(self.fhir, self.patient_id, self.encounter_id)
    fhir_patient_summary(self.fhir, self.patient_id)

    # 3) Load PGP form data
    fetch_pgp(self.fhir, self.patient_id)
    create_pgp_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_pgp(self.fhir, self.patient_id)


    # 4) Transfer patient to Lab flow
    new_location_id = choose_existing_location(self.fhir)
    if new_location_id and self.encounter_id:
        transfer_encounter_location(self.fhir, self.encounter_id, new_location_id)


