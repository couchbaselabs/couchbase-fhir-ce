import {readFile, lstat} from 'node:fs/promises';
import {existsSync } from 'node:fs'
import yaml from 'js-yaml';
import { auth } from './auth';
import Database from 'better-sqlite3';
import { stat } from 'node:fs';

async function seedAdmin() {
    const exists = existsSync("config.yaml");
    if(!exists) {
        console.log("config.yaml file doesn't exist. Skipping initialization and seeding ❗")
        return;
    }
    const fileContents = await readFile("config.yaml","utf-8");
    const config = yaml.load(fileContents) as Record<string,any>;

    if(!config.admin || !config.admin.email || !config.admin.password) {
        console.error("Admin User Configuration not found. Skipping Initialization and seeding ❗");
    }

    const db = new Database("db/auth.db");
    const user = db.prepare("SELECT * FROM user where email = ?").get(config.admin.email);

    if(user) {
        console.warn(`User Already exists with email: ${config.admin.email}. Skipping Seeding ❌`)
    }
    else {
        const user = await auth.api.createUser({
            body: {
                email: config.admin.email,
                password: config.admin.password,
                name: config.admin?.name ?? "Admin",
                role: "admin"
            }
        })

        console.log("Admin User seeded successfully 🌱")
    }
}

seedAdmin()