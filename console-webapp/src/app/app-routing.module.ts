import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import {TldsComponent} from './tlds/tlds.component';
import {HomeComponent} from './home/home.component';

const routes: Routes = [
  { path: 'home', component: HomeComponent },
  { path: 'tlds', component: TldsComponent },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
