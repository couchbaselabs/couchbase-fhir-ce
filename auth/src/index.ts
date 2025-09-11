import express from 'express';
import { AddressInfo } from 'node:net';
import { auth } from './auth';
import { toNodeHandler } from 'better-auth/node'
import cors from 'cors'
import 'dotenv/config'
import './seed';
const app = express()

// app.all("/auth/*splat",(req, res) => {
//     console.log(req.path)
//     res.send({ msg: "Hello"})
// })

app.use(cors({
    origin: true,
    methods: ['GET','POST','PUT','DELETE','PATCH'],
    credentials: true
}))

app.all("/auth/*splat",toNodeHandler(auth))


app.use(express.json())

const listener = app.listen(5000, '0.0.0.0',(err) => {
    if (err) console.error("Couldn't start server: ",err)
    const address = listener.address() as unknown as AddressInfo;
    console.info("Server is listening at "+address.address+":"+address.port)
})