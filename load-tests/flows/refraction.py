from faker import Faker

from data_entry.anterior import create_anterior_chamber_form_with_fake_data, fetch_ac
from data_entry.auto_refractometer import create_auto_refractometer_form_with_fake_data, fetch_auto_refractometer
from data_entry.iop import create_iop_form_with_fake_data, fetch_iop
from data_entry.screening import create_screening_form_with_fake_data, fetch_screening
from data_entry.posterior import create_posterior_chamber_form_with_fake_data, fetch_pc
from resources.practitioner import choose_existing_practitioner
from resources.location import choose_existing_location
from resources.encounter import get_encounters_with_details, transfer_encounter_location
from resources.summary import fhir_patient_sidebar, fhir_patient_summary


fake = Faker()


def refraction_flow(self):
    print("\n--------- [refraction flow start]")
    # 1) Run dashboard-style query using the first patient_list config
    practitioner_id = choose_existing_practitioner(self.fhir) or self.practitioner_id
    get_encounters_with_details(self.fhir)

    # 2) Query sidebar and patient summary data
    fhir_patient_sidebar(self.fhir, self.patient_id, self.encounter_id)
    fhir_patient_summary(self.fhir, self.patient_id)

    # 3) Auto-refractometer: fetch, create, fetch using FHIR
    fetch_auto_refractometer(self.fhir, self.patient_id, self.encounter_id)
    create_auto_refractometer_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_auto_refractometer(self.fhir, self.patient_id, self.encounter_id)

    # IOP form: fetch, create, fetch using FHIR
    fetch_iop(self.fhir, self.patient_id, self.encounter_id)
    create_iop_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_iop(self.fhir, self.patient_id, self.encounter_id)

    # Screening equivalents in refraction phase (optional quick vitals)
    fetch_screening(self.fhir, self.patient_id)
    create_screening_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_screening(self.fhir, self.patient_id)

    # Anterior chamber form: fetch, create, fetch using FHIR
    fetch_ac(self.fhir, self.patient_id)
    create_anterior_chamber_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_ac(self.fhir, self.patient_id)

    # Posterior chamber form: fetch, create, fetch using FHIR
    fetch_pc(self.fhir, self.patient_id)
    create_posterior_chamber_form_with_fake_data(self.fhir, self.patient_id, self.encounter_id, practitioner_id)
    fetch_pc(self.fhir, self.patient_id)

    # Transfer to next room
    new_location_id = choose_existing_location(self.fhir)
    if new_location_id and self.encounter_id:
        transfer_encounter_location(self.fhir, self.encounter_id, new_location_id)


