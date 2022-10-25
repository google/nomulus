import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import {MatCardModule} from '@angular/material/card';
import {MatTableModule} from '@angular/material/table'

import { HomeComponent } from './home/home.component';
import { TldsComponent } from './tlds/tlds.component';

@NgModule({
  declarations: [
    AppComponent,
    HomeComponent,
    TldsComponent,
  ],
  imports: [
    MatCardModule,
    MatTableModule,
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
