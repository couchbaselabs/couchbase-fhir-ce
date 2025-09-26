from faker import Faker

from resources.patient import get_or_create_patient_by_identifier
from resources.practitioner import get_or_create_practitioner
from resources.location import  get_or_create_location
from resources.encounter import create_encounter, get_encounters_with_details


fake = Faker()


def registration_flow(self):
    get_encounters_with_details(self.fhir)
    patient_id = get_or_create_patient_by_identifier(self.fhir, self.identifier)
    if not patient_id:
        return
    self.patient_id = patient_id

    practitioner_id = get_or_create_practitioner(self.fhir)
    # Prefer existing seeded location; fallback create if none present
    location_id = get_or_create_location(self.fhir)

    encounter_id = create_encounter(self.fhir, patient_id, practitioner_id, location_id)
    self.encounter_id = encounter_id
    self.location_id = location_id
    self.practitioner_id = practitioner_id


