import { betterAuth, Auth } from 'better-auth';
import {jwt, oidcProvider, admin} from 'better-auth/plugins'
import Database from 'better-sqlite3';

export const auth = betterAuth({
    trustedOrigins: ["http://localhost:5173","http://localhost"],
    emailAndPassword: {
        enabled: true,
    },
    database: new Database("db/auth.db"),
    basePath: "/auth",
    // disabledPaths: ["/token"],
    plugins:[
        jwt(),
        // oidcProvider({
        //     useJWTPlugin: true,
        //     loginPage: '/login',
        // }),
        admin()
    ]
})

