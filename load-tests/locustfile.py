from locust import FastHttpUser, task, between
from faker import Faker
from uuid import uuid4

from auth import build_optional_auth_headers
from resources.practitioner import choose_existing_practitioner
from flows.lab import lab_flow
from flows.pgp import ppg_flow
from resources.client import FHIRClient
from flows.registration import registration_flow
from flows.refraction import refraction_flow
from flows.subjective_refraction import subjective_refraction_flow
from flows.prescription import prescription_flow

# Optional auth headers
HEADERS = build_optional_auth_headers()

fake = Faker()
class FHIRScenario(FastHttpUser):
    wait_time = between(1, 3)

    def on_start(self):
        self.fhir = FHIRClient(self.client, base_url='/', headers=HEADERS)
        self.identifier = uuid4().hex
        self.practitioner_id = choose_existing_practitioner(self.fhir)

    @task
    def patient_movement(self):
        registration_flow(self)
        refraction_flow(self)
        subjective_refraction_flow(self)
        ppg_flow(self)
        lab_flow(self)
        prescription_flow(self)



